package scraper.plans.logical

import scraper.Catalog
import scraper.exceptions.{AnalysisException, ResolutionFailureException}
import scraper.expressions.{Star, UnresolvedAttribute}
import scraper.plans.logical.Analyzer._
import scraper.plans.logical.patterns._
import scraper.trees.RulesExecutor.FixedPoint
import scraper.trees.{Rule, RulesExecutor}

class Analyzer(catalog: Catalog) extends RulesExecutor[LogicalPlan] {
  override def batches: Seq[RuleBatch] = Seq(
    RuleBatch("Resolution", FixedPoint.Unlimited, Seq(
      ExpandStars,
      new ResolveRelations(catalog),
      ResolveReferences,
      ResolveSelfJoins,
      ApplyImplicitCasts
    ))
  )

  override def apply(tree: LogicalPlan): LogicalPlan = {
    logTrace(
      s"""Analyzing logical query plan:
         |
         |${tree.prettyTree}
         |""".stripMargin
    )
    super.apply(tree)
  }
}

object Analyzer {
  /**
   * This rule expands "`*`" appearing in `SELECT`.
   */
  object ExpandStars extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case Unresolved(Resolved(child) Project projectList) =>
        child select (projectList flatMap {
          case Star => child.output
          case e    => Seq(e)
        })
    }
  }

  /**
   * This rule tries to resolve [[scraper.expressions.UnresolvedAttribute UnresolvedAttribute]]s in
   * an logical plan operator using output [[scraper.expressions.Attribute Attribute]]s of its
   * children.
   */
  @throws[ResolutionFailureException](
    "If no candidate or multiple ambiguous candidate input attributes can be found"
  )
  object ResolveReferences extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case Unresolved(plan) if plan.children forall (_.strictlyTyped) =>
        plan transformExpressionsUp {
          case UnresolvedAttribute(name) =>
            def reportResolutionFailure(message: String): Nothing = {
              throw new ResolutionFailureException(
                s"""Failed to resolve attribute $name in logical query plan:
                   |
                   |${plan.prettyTree}
                   |
                   |$message
                   |""".stripMargin
              )
            }

            val candidates = plan.children flatMap (_.output) filter (_.name == name)

            candidates match {
              case Seq(attribute) =>
                attribute

              case Nil =>
                reportResolutionFailure("No candidate input attribute(s) found")

              case _ =>
                reportResolutionFailure {
                  val list = candidates map (_.debugString) mkString ", "
                  s"Multiple ambiguous input attributes found: $list"
                }
            }
        }
    }
  }

  class ResolveRelations(catalog: Catalog) extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case UnresolvedRelation(name) => catalog lookupRelation name
    }
  }

  /**
   * This rule tries to transform all resolved logical plans operators (and expressions within them)
   * into strictly typed form by applying implicit casts when necessary.
   *
   * @note This rule doesn't apply implicit casts directly. Instead, it simply delegates to
   *       [[scraper.plans.logical.LogicalPlan.strictlyTypedForm LogicalPlan.strictlyTypedForm]].
   */
  @throws[AnalysisException]("If some resolved logical query plan operator doesn't type check")
  object ApplyImplicitCasts extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case Resolved(plan) => plan.strictlyTypedForm.get
    }
  }

  /**
   * This rule eliminates all [[scraper.plans.logical.Subquery Subquery]] operators, since they are
   * only used to provide scoping information during analysis phase.
   */
  object EliminateSubqueries extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Subquery _ => plan
    }
  }

  object ResolveSelfJoins extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case Resolved(plan @ Join(left, right, _, _)) if left.output == right.output =>
        throw new UnsupportedOperationException("Self-join is not supported yet")
    }
  }
}
