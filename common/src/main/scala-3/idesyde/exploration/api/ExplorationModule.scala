package idesyde.exploration.api

import idesyde.exploration.interfaces.Explorer

trait ExplorationModule {
  def explorers: Set[Explorer]
}