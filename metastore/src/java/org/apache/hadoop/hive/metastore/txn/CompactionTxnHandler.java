/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore.txn;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.*;
import org.apache.hadoop.util.StringUtils;

import java.sql.*;
import java.util.*;

/**
 * Extends the transaction handler with methods needed only by the compactor threads.  These
 * methods are not available through the thrift interface.
 */
public class CompactionTxnHandler extends TxnHandler {
  static final private String CLASS_NAME = CompactionTxnHandler.class.getName();
  static final private Log LOG = LogFactory.getLog(CLASS_NAME);

  // Always access COMPACTION_QUEUE before COMPLETED_TXN_COMPONENTS
  // See TxnHandler for notes on how to deal with deadlocks.  Follow those notes.

  public CompactionTxnHandler(HiveConf conf) {
    super(conf);
  }

  /**
   * This will look through the completed_txn_components table and look for partitions or tables
   * that may be ready for compaction.  Also, look through txns and txn_components tables for
   * aborted transactions that we should add to the list.
   * @param maxAborted Maximum number of aborted queries to allow before marking this as a
   *                   potential compaction.
   * @return list of CompactionInfo structs.  These will not have id, type,
   * or runAs set since these are only potential compactions not actual ones.
   */
  public Set<CompactionInfo> findPotentialCompactions(int maxAborted) throws MetaException {
    Connection dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
    Set<CompactionInfo> response = new HashSet<CompactionInfo>();
    Statement stmt = null;
    try {
      stmt = dbConn.createStatement();
      // Check for completed transactions
      String s = "select distinct ctc_database, ctc_table, " +
          "ctc_partition from COMPLETED_TXN_COMPONENTS";
      LOG.debug("Going to execute query <" + s + ">");
      ResultSet rs = stmt.executeQuery(s);
      while (rs.next()) {
        CompactionInfo info = new CompactionInfo();
        info.dbname = rs.getString(1);
        info.tableName = rs.getString(2);
        info.partName = rs.getString(3);
        response.add(info);
      }

      // Check for aborted txns
      s = "select tc_database, tc_table, tc_partition " +
          "from TXNS, TXN_COMPONENTS " +
          "where txn_id = tc_txnid and txn_state = '" + TXN_ABORTED + "' " +
          "group by tc_database, tc_table, tc_partition " +
          "having count(*) > " + maxAborted;

      LOG.debug("Going to execute query <" + s + ">");
      rs = stmt.executeQuery(s);
      while (rs.next()) {
        CompactionInfo info = new CompactionInfo();
        info.dbname = rs.getString(1);
        info.tableName = rs.getString(2);
        info.partName = rs.getString(3);
        info.tooManyAborts = true;
        response.add(info);
      }

      LOG.debug("Going to rollback");
      dbConn.rollback();
    } catch (SQLException e) {
      LOG.error("Unable to connect to transaction database " + e.getMessage());
    } finally {
      closeDbConn(dbConn);
      closeStmt(stmt);
    }
    return response;
  }

  /**
   * Sets the user to run as.  This is for the case
   * where the request was generated by the user and so the worker must set this value later.
   * @param cq_id id of this entry in the queue
   * @param user user to run the jobs as
   */
  public void setRunAs(long cq_id, String user) throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      Statement stmt = null;
      try {
       stmt = dbConn.createStatement();
       String s = "update COMPACTION_QUEUE set cq_run_as = '" + user + "' where cq_id = " + cq_id;
       LOG.debug("Going to execute update <" + s + ">");
       if (stmt.executeUpdate(s) != 1) {
         LOG.error("Unable to update compaction record");
         LOG.debug("Going to rollback");
         dbConn.rollback();
       }
       LOG.debug("Going to commit");
       dbConn.commit();
     } catch (SQLException e) {
       LOG.error("Unable to update compaction queue, " + e.getMessage());
       try {
         LOG.debug("Going to rollback");
         dbConn.rollback();
       } catch (SQLException e1) {
       }
       detectDeadlock(e, "setRunAs");
     } finally {
       closeDbConn(dbConn);
       closeStmt(stmt);
     }
    } catch (DeadlockException e) {
      setRunAs(cq_id, user);
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * This will grab the next compaction request off of
   * the queue, and assign it to the worker.
   * @param workerId id of the worker calling this, will be recorded in the db
   * @return an info element for this compaction request, or null if there is no work to do now.
   */
  public CompactionInfo findNextToCompact(String workerId) throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      CompactionInfo info = new CompactionInfo();

      Statement stmt = null;
      try {
        stmt = dbConn.createStatement();
        String s = "select cq_id, cq_database, cq_table, cq_partition, " +
            "cq_type from COMPACTION_QUEUE where cq_state = '" + INITIATED_STATE + "'";
        LOG.debug("Going to execute query <" + s + ">");
        ResultSet rs = stmt.executeQuery(s);
        if (!rs.next()) {
          LOG.debug("No compactions found ready to compact");
          dbConn.rollback();
          return null;
        }
        info.id = rs.getLong(1);
        info.dbname = rs.getString(2);
        info.tableName = rs.getString(3);
        info.partName = rs.getString(4);
        switch (rs.getString(5).charAt(0)) {
          case MAJOR_TYPE: info.type = CompactionType.MAJOR; break;
          case MINOR_TYPE: info.type = CompactionType.MINOR; break;
          default: throw new MetaException("Unexpected compaction type " + rs.getString(5));
        }

        // Now, update this record as being worked on by this worker.
        long now = getDbTime(dbConn);
        s = "update COMPACTION_QUEUE set cq_worker_id = '" + workerId + "', " +
            "cq_start = " + now + ", cq_state = '" + WORKING_STATE + "' where cq_id = " + info.id;
        LOG.debug("Going to execute update <" + s + ">");
        if (stmt.executeUpdate(s) != 1) {
          LOG.error("Unable to update compaction record");
          LOG.debug("Going to rollback");
          dbConn.rollback();
        }
        LOG.debug("Going to commit");
        dbConn.commit();
        return info;
      } catch (SQLException e) {
        LOG.error("Unable to select next element for compaction, " + e.getMessage());
        try {
          LOG.debug("Going to rollback");
          dbConn.rollback();
        } catch (SQLException e1) {
        }
        detectDeadlock(e, "findNextToCompact");
        throw new MetaException("Unable to connect to transaction database " +
            StringUtils.stringifyException(e));
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (DeadlockException e) {
      return findNextToCompact(workerId);
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * This will mark an entry in the queue as compacted
   * and put it in the ready to clean state.
   * @param info info on the compaciton entry to mark as compacted.
   */
  public void markCompacted(CompactionInfo info) throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      Statement stmt = null;
      try {
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_state = '" + READY_FOR_CLEANING + "', " +
            "cq_worker_id = null where cq_id = " + info.id;
        LOG.debug("Going to execute update <" + s + ">");
        if (stmt.executeUpdate(s) != 1) {
          LOG.error("Unable to update compaction record");
          LOG.debug("Going to rollback");
          dbConn.rollback();
        }
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        try {
          LOG.error("Unable to update compaction queue " + e.getMessage());
          LOG.debug("Going to rollback");
          dbConn.rollback();
        } catch (SQLException e1) {
        }
        detectDeadlock(e, "markCompacted");
        throw new MetaException("Unable to connect to transaction database " +
            StringUtils.stringifyException(e));
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (DeadlockException e) {
      markCompacted(info);
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * Find entries in the queue that are ready to
   * be cleaned.
   * @return information on the entry in the queue.
   */
  public List<CompactionInfo> findReadyToClean() throws MetaException {
    Connection dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
    List<CompactionInfo> rc = new ArrayList<CompactionInfo>();

    Statement stmt = null;
    try {
      stmt = dbConn.createStatement();
      String s = "select cq_id, cq_database, cq_table, cq_partition, " +
          "cq_type, cq_run_as from COMPACTION_QUEUE where cq_state = '" + READY_FOR_CLEANING + "'";
      LOG.debug("Going to execute query <" + s + ">");
      ResultSet rs = stmt.executeQuery(s);
      while (rs.next()) {
        CompactionInfo info = new CompactionInfo();
        info.id = rs.getLong(1);
        info.dbname = rs.getString(2);
        info.tableName = rs.getString(3);
        info.partName = rs.getString(4);
        switch (rs.getString(5).charAt(0)) {
          case MAJOR_TYPE: info.type = CompactionType.MAJOR; break;
          case MINOR_TYPE: info.type = CompactionType.MINOR; break;
          default: throw new MetaException("Unexpected compaction type " + rs.getString(5));
        }
        info.runAs = rs.getString(6);
        rc.add(info);
      }
      LOG.debug("Going to rollback");
      dbConn.rollback();
      return rc;
    } catch (SQLException e) {
      LOG.error("Unable to select next element for cleaning, " + e.getMessage());
      try {
        LOG.debug("Going to rollback");
        dbConn.rollback();
      } catch (SQLException e1) {
      }
      throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
    } finally {
      closeDbConn(dbConn);
      closeStmt(stmt);
    }
  }

  /**
   * This will remove an entry from the queue after
   * it has been compacted.
   * @param info info on the compaction entry to remove
   */
  public void markCleaned(CompactionInfo info) throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      Statement stmt = null;
      try {
        stmt = dbConn.createStatement();
        String s = "delete from COMPACTION_QUEUE where cq_id = " + info.id;
        LOG.debug("Going to execute update <" + s + ">");
        if (stmt.executeUpdate(s) != 1) {
          LOG.error("Unable to delete compaction record");
          LOG.debug("Going to rollback");
          dbConn.rollback();
        }

        // Remove entries from completed_txn_components as well, so we don't start looking there
        // again.
        s = "delete from COMPLETED_TXN_COMPONENTS where ctc_database = '" + info.dbname + "' and " +
            "ctc_table = '" + info.tableName + "'";
        if (info.partName != null) {
          s += " and ctc_partition = '" + info.partName + "'";
        }
        LOG.debug("Going to execute update <" + s + ">");
        if (stmt.executeUpdate(s) < 1) {
          LOG.error("Expected to remove at least one row from completed_txn_components when " +
              "marking compaction entry as clean!");
        }


        s = "select txn_id from TXNS, TXN_COMPONENTS where txn_id = tc_txnid and txn_state = '" +
            TXN_ABORTED + "' and tc_database = '" + info.dbname + "' and tc_table = '" +
            info.tableName + "'";
        if (info.partName != null) s += " and tc_partition = '" + info.partName + "'";
        LOG.debug("Going to execute update <" + s + ">");
        ResultSet rs = stmt.executeQuery(s);
        Set<Long> txnids = new HashSet<Long>();
        while (rs.next()) txnids.add(rs.getLong(1));
        if (txnids.size() > 0) {

          // Remove entries from txn_components, as there may be aborted txn components
          StringBuffer buf = new StringBuffer();
          buf.append("delete from TXN_COMPONENTS where tc_txnid in (");
          boolean first = true;
          for (long id : txnids) {
            if (first) first = false;
            else buf.append(", ");
            buf.append(id);
          }

          buf.append(") and tc_database = '");
          buf.append(info.dbname);
          buf.append("' and tc_table = '");
          buf.append(info.tableName);
          buf.append("'");
          if (info.partName != null) {
            buf.append(" and tc_partition = '");
            buf.append(info.partName);
            buf.append("'");
          }
          LOG.debug("Going to execute update <" + buf.toString() + ">");
          int rc = stmt.executeUpdate(buf.toString());
          LOG.debug("Removed " + rc + " records from txn_components");

          // Don't bother cleaning from the txns table.  A separate call will do that.  We don't
          // know here which txns still have components from other tables or partitions in the
          // table, so we don't know which ones we can and cannot clean.
        }

        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        try {
          LOG.error("Unable to delete from compaction queue " + e.getMessage());
          LOG.debug("Going to rollback");
          dbConn.rollback();
        } catch (SQLException e1) {
        }
        detectDeadlock(e, "markCleaned");
        throw new MetaException("Unable to connect to transaction database " +
            StringUtils.stringifyException(e));
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (DeadlockException e) {
      markCleaned(info);
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * Clean up aborted transactions from txns that have no components in txn_components.
   */
  public void cleanEmptyAbortedTxns() throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      Statement stmt = null;
      try {
        stmt = dbConn.createStatement();
        String s = "select txn_id from TXNS where " +
            "txn_id not in (select tc_txnid from TXN_COMPONENTS) and " +
            "txn_state = '" + TXN_ABORTED + "'";
        LOG.debug("Going to execute query <" + s + ">");
        ResultSet rs = stmt.executeQuery(s);
        Set<Long> txnids = new HashSet<Long>();
        while (rs.next()) txnids.add(rs.getLong(1));
        if (txnids.size() > 0) {
          StringBuffer buf = new StringBuffer("delete from TXNS where txn_id in (");
          boolean first = true;
          for (long tid : txnids) {
            if (first) first = false;
            else buf.append(", ");
            buf.append(tid);
          }
          buf.append(")");
          LOG.debug("Going to execute update <" + buf.toString() + ">");
          int rc = stmt.executeUpdate(buf.toString());
          LOG.debug("Removed " + rc + " records from txns");
          LOG.debug("Going to commit");
          dbConn.commit();
        }
      } catch (SQLException e) {
        LOG.error("Unable to delete from txns table " + e.getMessage());
        LOG.debug("Going to rollback");
        try {
          dbConn.rollback();
        } catch (SQLException e1) {
        }
        detectDeadlock(e, "cleanEmptyAbortedTxns");
        throw new MetaException("Unable to connect to transaction database " +
            StringUtils.stringifyException(e));
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (DeadlockException e) {
      cleanEmptyAbortedTxns();
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * This will take all entries assigned to workers
   * on a host return them to INITIATED state.  The initiator should use this at start up to
   * clean entries from any workers that were in the middle of compacting when the metastore
   * shutdown.  It does not reset entries from worker threads on other hosts as those may still
   * be working.
   * @param hostname Name of this host.  It is assumed this prefixes the thread's worker id,
   *                 so that like hostname% will match the worker id.
   */
  public void revokeFromLocalWorkers(String hostname) throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      Statement stmt = null;
      try {
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_worker_id = null, cq_start = null, cq_state = '"
            + INITIATED_STATE+ "' where cq_state = '" + WORKING_STATE + "' and cq_worker_id like '"
            +  hostname + "%'";
        LOG.debug("Going to execute update <" + s + ">");
        // It isn't an error if the following returns no rows, as the local workers could have died
        // with  nothing assigned to them.
        stmt.executeUpdate(s);
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        try {
          LOG.error("Unable to change dead worker's records back to initiated state " +
              e.getMessage());
          LOG.debug("Going to rollback");
          dbConn.rollback();
        } catch (SQLException e1) {
        }
        detectDeadlock(e, "revokeFromLocalWorkers");
        throw new MetaException("Unable to connect to transaction database " +
            StringUtils.stringifyException(e));
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (DeadlockException e) {
      revokeFromLocalWorkers(hostname);
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * This call will return all compaction queue
   * entries assigned to a worker but over the timeout back to the initiated state.
   * This should be called by the initiator on start up and occasionally when running to clean up
   * after dead threads.  At start up {@link #revokeFromLocalWorkers(String)} should be called
   * first.
   * @param timeout number of milliseconds since start time that should elapse before a worker is
   *                declared dead.
   */
  public void revokeTimedoutWorkers(long timeout) throws MetaException {
    try {
      Connection dbConn = getDbConn(Connection.TRANSACTION_SERIALIZABLE);
      long latestValidStart = getDbTime(dbConn) - timeout;
      Statement stmt = null;
      try {
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_worker_id = null, cq_start = null, cq_state = '"
            + INITIATED_STATE+ "' where cq_state = '" + WORKING_STATE + "' and cq_start < "
            +  latestValidStart;
        LOG.debug("Going to execute update <" + s + ">");
        // It isn't an error if the following returns no rows, as the local workers could have died
        // with  nothing assigned to them.
        stmt.executeUpdate(s);
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        try {
          LOG.error("Unable to change dead worker's records back to initiated state " +
              e.getMessage());
          LOG.debug("Going to rollback");
          dbConn.rollback();
        } catch (SQLException e1) {
        }
        detectDeadlock(e, "revokeTimedoutWorkers");
        throw new MetaException("Unable to connect to transaction database " +
            StringUtils.stringifyException(e));
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (DeadlockException e) {
      revokeTimedoutWorkers(timeout);
    } finally {
      deadlockCnt = 0;
    }
  }

  /**
   * Queries metastore DB directly to find columns in the table which have statistics information.
   * If {@code ci} includes partition info then per partition stats info is examined, otherwise
   * table level stats are examined.
   * @throws MetaException
   */
  public List<String> findColumnsWithStats(CompactionInfo ci) throws MetaException {
    Connection dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
    Statement stmt = null;
    ResultSet rs = null;
    try {
      String quote = getIdentifierQuoteString(dbConn);
      stmt = dbConn.createStatement();
      StringBuilder bldr = new StringBuilder();
      bldr.append("SELECT ").append(quote).append("COLUMN_NAME").append(quote)
          .append(" FROM ")
          .append(quote).append((ci.partName == null ? "TAB_COL_STATS" : "PART_COL_STATS"))
              .append(quote)
          .append(" WHERE ")
          .append(quote).append("DB_NAME").append(quote).append(" = '").append(ci.dbname)
              .append("' AND ").append(quote).append("TABLE_NAME").append(quote)
              .append(" = '").append(ci.tableName).append("'");
      if (ci.partName != null) {
        bldr.append(" AND ").append(quote).append("PARTITION_NAME").append(quote).append(" = '")
            .append(ci.partName).append("'");
      }
      String s = bldr.toString();

      /*String s = "SELECT COLUMN_NAME FROM " + (ci.partName == null ? "TAB_COL_STATS" :
          "PART_COL_STATS")
         + " WHERE DB_NAME='" + ci.dbname + "' AND TABLE_NAME='" + ci.tableName + "'"
        + (ci.partName == null ? "" : " AND PARTITION_NAME='" + ci.partName + "'");*/
      LOG.debug("Going to execute <" + s + ">");
      rs = stmt.executeQuery(s);
      List<String> columns = new ArrayList<String>();
      while(rs.next()) {
        columns.add(rs.getString(1));
      }
      LOG.debug("Found columns to update stats: " + columns + " on " + ci.tableName +
        (ci.partName == null ? "" : "/" + ci.partName));
      dbConn.commit();
      return columns;
    } catch (SQLException e) {
      try {
        LOG.error("Failed to find columns to analyze stats on for " + ci.tableName +
            (ci.partName == null ? "" : "/" + ci.partName), e);
        dbConn.rollback();
      } catch (SQLException e1) {
        //nothing we can do here
      }
      throw new MetaException("Unable to connect to transaction database " +
        StringUtils.stringifyException(e));
    } finally {
      close(rs, stmt, dbConn);
    }
  }
}


