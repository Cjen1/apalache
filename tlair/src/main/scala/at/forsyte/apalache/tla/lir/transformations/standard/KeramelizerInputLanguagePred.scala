package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.ApalacheOper.gen
import at.forsyte.apalache.tla.lir.oper._
import at.forsyte.apalache.tla.lir.transformations.{PredResult, PredResultFail, PredResultOk}

import scala.collection.immutable.HashSet

/**
 * Test whether expressions fit into the input fragment of [[Keramelizer]], i.e., whether an expression
 *   - is flattened (see [[FlatLanguagePred]]),
 *   - operators `exists`, `forall`, `chooseBounded`, `filter`, `apply` take a [[NameEx]] as first argument, and
 *   - `Seq(_)` is prohibited.
 *
 * To get a better idea of the accepted fragment, check [[TestKeramelizerInputLanguagePred]].
 */
class KeramelizerInputLanguagePred extends ContextualLanguagePred {
  override def isExprOk(expr: TlaEx): PredResult = {
    FlatLanguagePred().isExprOk(expr).and(isOkInContext(Set(), expr))
  }

  override protected def isOkInContext(letDefs: Set[String], expr: TlaEx): PredResult = {
    // Check if `set` is typed as SetT1(_).
    def isSetTypeOk(set: TlaEx): PredResult = set.typeTag match {
      case Typed(SetT1(_)) => PredResultOk()
      case t               => PredResultFail(Seq((expr.ID, s"Expected a set, found: $t")))
    }
    // Check if set arguments are typed as required by Keramelizer.
    val setArgsAreTyped = expr match {
      // intersection
      case OperEx(TlaSetOper.cap, setX, _) => isSetTypeOk(setX)
      // set difference
      case OperEx(TlaSetOper.setminus, setX, _) => isSetTypeOk(setX)
      // set of records
      case OperEx(TlaSetOper.recSet, keysAndSets @ _*) => {
        val (_, sets) = TlaOper.deinterleave(keysAndSets)
        sets.foldLeft(isSetTypeOk(expr)) { (isOk, set) => isOk.and(isSetTypeOk(set)) }
      }
      // cartesian product \X
      case OperEx(TlaSetOper.times, sets @ _*) =>
        sets.foldLeft[PredResult](PredResultOk()) { (isOk, set) => isOk.and(isSetTypeOk(set)) }
      // assignment-like expressions
      case OperEx(TlaSetOper.in, _ @OperEx(TlaActionOper.prime, NameEx(_)), set) => isSetTypeOk(set)
      // no further requirements
      case _ => PredResultOk()
    }
    // Check if expression is in the input fragment of Keramelizer.
    val isValidKeramelizerInput = expr match {
      // `exists`, `forall`, `chooseBounded`, `filter`, and `apply` require a name as first argument
      case OperEx(oper, arg, args @ _*) if KeramelizerInputLanguagePred.nameExOps.contains(oper) =>
        arg match {
          case NameEx(_) =>
            args.foldLeft[PredResult](PredResultOk()) { case (r, arg) =>
              r.and(isOkInContext(letDefs, arg))
            }
          case _ => PredResultFail(Seq((expr.ID, s"first argument not a name: $arg of $expr")))
        }
      // `Seq(_)` is prohibited
      case OperEx(TlaSetOper.seqSet, _) =>
        PredResultFail(Seq((expr.ID,
                    "Seq(_) produces an infinite set of unbounded sequences. See: " + KeramelizerInputLanguagePred.MANUAL_LINK_SEQ)))
      // recurse into operators
      case OperEx(_, args @ _*) =>
        args.foldLeft[PredResult](PredResultOk()) { case (r, arg) =>
          r.and(isOkInContext(letDefs, arg))
        }
      // recurse into let-in's
      case LetInEx(body, defs @ _*) =>
        // check the let-definitions first, in a sequence, as they may refer to each other
        val defsResult = eachDefRec(letDefs, defs.toList)
        val newLetDefs = defs.map(_.name).toSet
        // check the terminal expression in the LET-IN chain, by assuming the context generated by the definitions
        defsResult
          .and(isOkInContext(letDefs ++ newLetDefs, body))
      // all other expressions are legal
      case _ => PredResultOk()
    }
    setArgsAreTyped.and(isValidKeramelizerInput)
  }
}

object KeramelizerInputLanguagePred {
  val MANUAL_LINK_SEQ = "https://apalache.informal.systems/docs/apalache/known-issues.html#using-seqs"

  private val singleton = new KeramelizerInputLanguagePred

  protected val nameExOps: HashSet[TlaOper] = HashSet(
      TlaBoolOper.exists,
      TlaBoolOper.forall,
      TlaOper.chooseBounded,
      TlaSetOper.filter,
      TlaOper.apply,
  )

  def apply(): KeramelizerInputLanguagePred = singleton
}
