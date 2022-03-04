package idesyde.identification.interfaces

import forsyde.io.java.core.ForSyDeSystemGraph
import idesyde.identification.DecisionModel
import org.chocosolver.solver.Model
import org.chocosolver.solver.variables.IntVar
import org.chocosolver.solver.variables.Variable
import org.chocosolver.solver.Solution
import org.chocosolver.solver.search.strategy.strategy.AbstractStrategy

trait ChocoCPDecisionModel extends DecisionModel:

  def chocoModel: Model

  def modelObjectives: Array[IntVar] = Array.empty

  def strategies: Array[AbstractStrategy[? <: Variable]] = Array.empty

  def rebuildFromChocoOutput(output: Solution): ForSyDeSystemGraph

end ChocoCPDecisionModel