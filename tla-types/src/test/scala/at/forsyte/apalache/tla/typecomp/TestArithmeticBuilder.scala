package at.forsyte.apalache.tla.typecomp

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.TlaArithOper
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestArithmeticBuilder extends BuilderTest {
  def testBinaryOperAndBuilderMethod(
      oper: TlaArithOper,
      method: (TBuilderInstruction, TBuilderInstruction) => TBuilderInstruction,
      retT: TlaType1): Unit = {
    val cmp = cmpFactory.computationFromSignature(oper)

    val args = Seq.fill(2)(builder.int(1))

    val res = cmp(args.map(build))

    assert(res.contains(retT))

    val Seq(x, y) = args
    val resEx = method(x, y)

    assert(resEx.eqTyped(OperEx(oper, x, y)(Typed(retT))))

    val badY = builder.str("a")

    assertThrows[TBuilderTypeException] {
      build(method(x, badY))
    }
  }

  test("plus") {
    testBinaryOperAndBuilderMethod(TlaArithOper.plus, builder.plus, IntT1)
  }

  test("minus") {
    testBinaryOperAndBuilderMethod(TlaArithOper.minus, builder.minus, IntT1)
  }

  test("uminus") {
    val cmp = cmpFactory.computationFromSignature(TlaArithOper.uminus)

    val x = builder.int(1)

    val res = cmp(Seq(build(x)))

    assert(res.contains(IntT1))

    val resEx = builder.uminus(x)

    assert(resEx.eqTyped(OperEx(TlaArithOper.uminus, x)(Typed(IntT1))))

    val badX = builder.str("a")

    assertThrows[TBuilderTypeException] {
      build(builder.uminus(badX))
    }
  }

  test("mult") {
    testBinaryOperAndBuilderMethod(TlaArithOper.mult, builder.mult, IntT1)
  }

  test("div") {
    testBinaryOperAndBuilderMethod(TlaArithOper.div, builder.div, IntT1)
  }

  test("mod") {
    testBinaryOperAndBuilderMethod(TlaArithOper.mod, builder.mod, IntT1)
  }

  test("exp") {
    testBinaryOperAndBuilderMethod(TlaArithOper.exp, builder.exp, IntT1)
  }

  test("dotdot") {
    testBinaryOperAndBuilderMethod(TlaArithOper.dotdot, builder.dotdot, SetT1(IntT1))
  }

  test("lt") {
    testBinaryOperAndBuilderMethod(TlaArithOper.lt, builder.lt, BoolT1)
  }

  test("gt") {
    testBinaryOperAndBuilderMethod(TlaArithOper.gt, builder.gt, BoolT1)
  }

  test("le") {
    testBinaryOperAndBuilderMethod(TlaArithOper.le, builder.le, BoolT1)
  }

  test("ge") {
    testBinaryOperAndBuilderMethod(TlaArithOper.ge, builder.ge, BoolT1)
  }

}
