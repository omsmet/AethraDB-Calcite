package org.apache.calcite.rex;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.runtime.Utilities;

import java.io.Serializable;

/**
 * Hardcoded for the Aethra-Planner-Lib.
 */
public class Reducer extends Utilities implements Function1<DataContext, Object[]>, Serializable {

  public Reducer() {
    super();
  }

  public Object[] apply(DataContext root0) {
    return new Object[] {
        org.apache.calcite.runtime.SqlFunctions.truncateOrPad("BUILDING", 10)};
  }

}
