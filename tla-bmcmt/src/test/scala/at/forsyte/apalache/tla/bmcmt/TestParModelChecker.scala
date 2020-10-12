package at.forsyte.apalache.tla.bmcmt

import java.io.File
import java.util.concurrent.TimeUnit

import at.forsyte.apalache.tla.bmcmt.analyses._
import at.forsyte.apalache.tla.bmcmt.search.{ModelCheckerParams, SharedSearchState, WorkerContext}
import at.forsyte.apalache.tla.bmcmt.smt.RecordingZ3SolverContext
import at.forsyte.apalache.tla.bmcmt.trex.{FilteredTransitionExecutor, OfflineExecutorContext, OfflineSnapshot, TransitionExecutorImpl}
import at.forsyte.apalache.tla.bmcmt.types.eager.TrivialTypeFinder
import at.forsyte.apalache.tla.imp.src.SourceStore
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.oper.BmcOper
import at.forsyte.apalache.tla.lir.storage.ChangeListener
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FunSuite}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

@RunWith(classOf[JUnitRunner])
class TestParModelChecker extends FunSuite with BeforeAndAfter {
  private var typeFinder: TrivialTypeFinder = new TrivialTypeFinder()
  private var exprGradeStore: ExprGradeStore = new ExprGradeStoreImpl()
  private var hintsStore: FormulaHintsStoreImpl = new FormulaHintsStoreImpl()
  private var sourceStore: SourceStore = new SourceStore()
  private var changeListener: ChangeListener = new ChangeListener()
  private var solver: RecordingZ3SolverContext = RecordingZ3SolverContext(None, debug = false, profile = false)
  private var rewriter = new SymbStateRewriterImpl(solver, typeFinder, exprGradeStore)
  private var sharedState = new SharedSearchState(1)

  before {
    // initialize the model checker
    typeFinder = new TrivialTypeFinder()
    exprGradeStore = new ExprGradeStoreImpl()
    sourceStore = new SourceStore()
    solver = RecordingZ3SolverContext(None, debug = false, profile = false)
    rewriter = new SymbStateRewriterImpl(solver, typeFinder, exprGradeStore)
    rewriter.formulaHintsStore = hintsStore
    sharedState = new SharedSearchState(1)
  }

  after {
    typeFinder.reset(Map())
  }

  private def mkModuleWithX(): TlaModule = {
    new TlaModule("root", List(TlaVarDecl("x")))
  }

  private def mkModuleWithXandY(): TlaModule = {
    new TlaModule("root", List(TlaVarDecl("x"), TlaVarDecl("y")))
  }

  private def mkWorkerContext(params: ModelCheckerParams,
                              rewriter: SymbStateRewriterImpl = rewriter,
                              rank: Int = 1): WorkerContext = {
    val trex = new TransitionExecutorImpl[OfflineSnapshot](params.consts, params.vars, new OfflineExecutorContext(rewriter))
    val filtered = new FilteredTransitionExecutor(params.transitionFilter, params.invFilter, trex)
    new WorkerContext(rank, sharedState.searchRoot, filtered)
  }

  test("Init, OK") {
    // x' \in {2}
    val initTrans = List(mkAssign("x", 2))
    val nextTrans = List(mkAssign("x", 2))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List.empty)
    val params = new ModelCheckerParams(checkerInput, stepsBound = 0, new File("."), Map(), false)

    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  // TODO: figure out how to detect deadlocks in the new algorithm
  ignore("Init, deadlock") {
    // x' \in {2} /\ x' \in {1}
    val initTrans = List(tla.and(mkAssign("x", 2), mkNotAssign("x", 1)))
    val nextTrans = List(mkAssign("x", 2))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List.empty)
    val params = new ModelCheckerParams(checkerInput, stepsBound = 0, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Deadlock == outcome)
  }

  test("Init, 2 options, OK") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    val nextTrans = List(mkAssign("x", 2))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List.empty)
    val params = new ModelCheckerParams(checkerInput, stepsBound = 0, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next, 1 step, OK") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List.empty)
    val params = new ModelCheckerParams(checkerInput, stepsBound = 1, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next, 1 step, formula hint") {
    // x' \in {1}
    val initTrans = List(mkAssign("x", 1))
    // x < 10 /\ x' \in {x + 1}
    val nextTrans =
      List(tla.and(
        tla.lt(tla.name("x"), tla.int(10)),
        mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
      )///
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List.empty)

    // Add the hint. We cannot check in the test, whether the hints was actually used.
    // We only check that the checker works in presence of hints.
    hintsStore.store.put(nextTrans.head.ID, FormulaHintsStore.HighAnd())
    val params = new ModelCheckerParams(checkerInput, stepsBound = 1, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("determinstic Init + 2 steps (regression)") {
    // y' \in {1} /\ x' \in {1}
    val initTrans = List(tla.and(mkAssign("y", 1), mkAssign("x", 1)))
    // y' \in {y + 1} /\ x' \in {x + 1}
    val nextTrans = List(tla.and(
      mkAssign("y", tla.plus(tla.name("y"), tla.int(1))),
      mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))
    ))///
    val inv = tla.eql(
      tla.eql(tla.int(3), tla.name("x")),
      tla.eql(tla.int(3), tla.name("y"))
    ) ////

    val checkerInput = new CheckerInput(mkModuleWithXandY(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 2, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + 2 steps with LET-IN") {
    // x' \in {1}
    val initTrans = List(mkAssign("x", 1))
    // LET A == 1 + x IN x' \in { A + 1 }
    val aDecl = TlaOperDecl("A", List(), tla.plus(tla.int(1), tla.name("x")))

    val letIn = tla.letIn(tla.plus(tla.appDecl(aDecl), tla.int(1)), aDecl)

    val nextTrans = List(mkAssign("x", letIn))
    val inv = tla.not(tla.eql(tla.int(4), tla.name("x")))

    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 2, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  // TODO: figure out how to detect deadlocks in the new algorithm
  ignore("Init + Next, 1 step, deadlock") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x > 3 /\ x' \in {x + 1}
    val nextTrans = List(
      tla.and(tla.gt(tla.name("x"), tla.int(3)),
        mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List())
    val params = new ModelCheckerParams(checkerInput, stepsBound = 1, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Deadlock == outcome)
  }

  test("Init + Next, 10 steps, OK") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List())
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  // TODO: figure out how to detect deadlocks in the new algorithm
  ignore("Init + Next, 10 steps, deadlock") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x < 10 /\ x' \in {x + 1}
    val nextTrans = List(
      tla.and(tla.lt(tla.name("x"), tla.int(10)),
        mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List())
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Deadlock == outcome)
  }

  test("Init + Next + Inv, 10 steps, OK") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x < 100
    val inv = tla.lt(tla.name("x"), tla.int(100))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next + Inv, 10 steps, learnFromUnsat, OK") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x < 100
    val inv = tla.lt(tla.name("x"), tla.int(100))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    // initialize the model checker
    val tuning = Map("search.invariant.learnFromUnsat" -> "true")
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), tuning, false)
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next + Inv, 10 steps, !search.invariant.split, OK") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x < 100
    val inv = tla.lt(tla.name("x"), tla.int(100))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    // initialize the model checker
    val tuning = Map("search.invariant.split" -> "false")
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), tuning, false)
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next + Inv, 10 steps, error") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x < 5
    val inv = tla.lt(tla.name("x"), tla.int(5))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Error == outcome)
  }

  test("Init + Next + Inv, 3 steps, error, edge case") {
    // the invariant is violated in the last state of a bounded execution

    // x' \in {0}
    val initTrans = List(mkAssign("x", 0))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x /= 3
    val inv = tla.not(tla.eql(tla.name("x"), tla.int(3)))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 3, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Error == outcome)
  }

  test("Init + Next + Inv, 2 steps, no error, edge case") {
    // the invariant is violated in the last state of a bounded execution

    // x' \in {0}
    val initTrans = List(mkAssign("x", 0))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x /= 3
    val inv = tla.not(tla.eql(tla.name("x"), tla.int(3)))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 2, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next + Inv, 10 steps, and invariantFilter") {
    // x' \in {2} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 2), mkAssign("x", 1))
    // x' \in {x + 1}
    val nextTrans = List(mkAssign("x", tla.plus(tla.name("x"), tla.int(1))))
    // x < 5
    val inv = tla.lt(tla.name("x"), tla.int(5))
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((inv, tla.not(inv))))
    // initialize the model checker
    // We require the invariant to be checked only after the second step. So we will miss invariant violation.
    val tuning = Map("search.invariantFilter" -> "2")
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), tuning, false)
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next, 3 steps, non-determinism but no deadlock") {
    // x' \in {1}
    val initTrans = List(mkAssign("x", 1))
    // x' \in {x + 1} \/ x > 100 /\ x' \in {x}
    val trans1 = mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))
    val trans2 = tla.and(tla.gt(tla.name("x"), tla.int(100)),
      mkAssign("x", tla.name("x")))
    val nextTrans = List(trans1, trans2)
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List())
    val params = new ModelCheckerParams(checkerInput, stepsBound = 3, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next + Inv, 2 steps, set assignments") {
    // sets require an explicit equality, and that is where picking the next state may fail
    // Init == x \in {2} /\ y \in {10}
    // Next == \/ x' = x \cup {2} /\ y' = y \setminus {11}
    //         \/ x' = x \setminus {2} /\ y' = y \cup {11}
    // Inv ==  11 \in y <=> 2 \notin x

    // Init == x' = {2} /\ y = {10}
    val init = tla.and(mkAssign("x", tla.enumSet(tla.int(2))),
      mkAssign("y", tla.enumSet(tla.int(10))))

    // as KerA+ does not have setminus, we use a filter here
    def setminus(setName: String, boundName: String, intVal: Int): TlaEx = {
      tla.filter(tla.name(boundName),
        tla.name(setName),
        tla.not(tla.eql(tla.name(boundName), tla.int(intVal))))
    }

    // Next == \/ x' = x \cup {2} /\ y' = y \setminus {11}
    //         \/ x' = x \setminus {2} /\ y' = y \cup {11}
    val next1 =
    tla.and(
      mkAssign("x", tla.cup(tla.name("x"), tla.enumSet(tla.int(2)))),
      mkAssign("y", setminus("y", "t1", 11))
    )
    ///
    ///
    val next2 =
    tla.and(
      mkAssign("x", setminus("x", "t2", 2)),
      mkAssign("y", tla.cup(tla.name("y"), tla.enumSet(tla.int(11))))
    ) ///

    // Inv ==  11 \in y <=> 2 \notin x
    val inv = tla.eql(
      tla.in(tla.int(11), tla.name("y")),
      tla.not(tla.in(tla.int(2), tla.name("x")))
    ) ////

    val checkerInput = new CheckerInput(mkModuleWithXandY(), List(init), List(next1, next2), None, List((inv, tla.not(inv))))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 2, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("Init + Next, 10 steps, non-determinism in init and next") {
    // x' \in {0} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 0), mkAssign("x", 1))
    // x' \in {x + 1} \/ x > 10 /\ x' \in {x}
    val trans1 = mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))
    val trans2 = tla.and(tla.gt(tla.name("x"), tla.int(10)),
      mkAssign("x", tla.name("x")))
    val nextTrans = List(trans1, trans2)
    val notInv = tla.gt(tla.name("x"), tla.int(10)) // ~(x <= 10)
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((tla.not(notInv), notInv)))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Error == outcome)
  }

  test("cInit + Init + Next, 10 steps") {
    // x' \in {0} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 0), mkAssign("x", 1))
    // x' \in {x + 1} \/ x > 10 /\ x' \in {x}
    val trans1 = mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))
    val trans2 = tla.and(tla.gt(tla.name("x"), tla.int(10)),
      mkAssign("x", tla.name("x")))
    val nextTrans = List(trans1, trans2)
    // a constant initializer: \E t \in { 20, 10 }: N' \in {t}
    val cInit =
      OperEx(BmcOper.skolem,
        tla.exists(
          tla.name("t"),
          tla.enumSet(tla.int(20), tla.int(10)),
          mkAssign("N", tla.name("t"))
        )) ////

    val notInv = tla.gt(tla.name("x"), tla.name("N")) // ~(x <= N)
    val dummyModule = new TlaModule("root", List(TlaConstDecl("N"), TlaVarDecl("x")))
    val checkerInput = new CheckerInput(dummyModule, initTrans, nextTrans, Some(cInit), List((tla.not(notInv), notInv)))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checker
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.Error == outcome)
  }

  test("Init + Next, 10 steps and filter") {
    // x' \in {0}
    val initTrans = List(mkAssign("x", 0))
    // x' \in {x + 1} \/ x' \in {x + 2}
    val trans1 = mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))
    val trans2 = mkAssign("x", tla.plus(tla.name("x"), tla.int(2)))
    val nextTrans = List(trans1, trans2)
    val notInv = tla.gt(tla.name("x"), tla.int(11)) // ~(x <= 11)
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((tla.not(notInv), notInv)))
    // initialize the model checker
    val filter = "([0-9]|10)->0" // execute initTrans once and trans1 10 times
    val tuning = Map("search.transitionFilter" -> filter)
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), tuning, false)
    val checker = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params))
    val outcome = checker.run()
    assert(Checker.Outcome.NoError == outcome)
  }

  test("2 workers: Init + Next, 10 steps") {
    // x' \in {0} \/ x' \in {1}
    val initTrans = List(mkAssign("x", 0), mkAssign("x", 1))
    // x' \in {x + 1} \/ x > 10 /\ x' \in {x}
    val trans1 = mkAssign("x", tla.plus(tla.name("x"), tla.int(1)))
    val trans2 = tla.and(tla.gt(tla.name("x"), tla.int(10)),
      mkAssign("x", tla.name("x")))
    val nextTrans = List(trans1, trans2)
    val notInv = tla.gt(tla.name("x"), tla.int(10)) // ~(x <= 10)
    val checkerInput = new CheckerInput(mkModuleWithX(), initTrans, nextTrans, None, List((tla.not(notInv), notInv)))
    val params = new ModelCheckerParams(checkerInput, stepsBound = 10, new File("."), Map(), false)
    // initialize the model checkers
    val sharedState = new SharedSearchState(2)

    val solver1 = RecordingZ3SolverContext(None, debug = false, profile = false)
    val typeFinder1 = new TrivialTypeFinder()
    val rewriter1 = new SymbStateRewriterImpl(solver1, typeFinder1, exprGradeStore)
    val checker1 = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params, rewriter1, 1))

    val solver2 = RecordingZ3SolverContext(None, debug = false, profile = false)
    val typeFinder2 = new TrivialTypeFinder
    val rewriter2 = new SymbStateRewriterImpl(solver2, typeFinder2, exprGradeStore)
    val checker2 = new ParModelChecker(checkerInput, params, sharedState, mkWorkerContext(params, rewriter2, 2))

    val future1 = Future {
      Thread.sleep(100)
      checker1.run()
    }
    val future2 = Future {
      Thread.sleep(70)
      checker2.run()
    }

    val outcome1 = Await.result(future1, Duration(10, TimeUnit.SECONDS))
    val outcome2 = Await.result(future2, Duration(10, TimeUnit.SECONDS))

    assert(Checker.Outcome.Error == outcome1 || Checker.Outcome.Error == outcome2)
  }

  private def mkAssign(name: String, value: Int) =
    tla.assignPrime(tla.name(name), tla.int(value))

  private def mkAssign(name: String, rhs: TlaEx) =
    tla.assignPrime(tla.name(name), rhs)

  private def mkNotAssign(name: String, value: Int) =
    tla.primeEq(tla.name(name), tla.int(value))

  private def mkNotAssign(name: String, rhs: TlaEx) =
    tla.primeEq(tla.name(name), rhs)
}