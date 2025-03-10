/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - Carlos Ruiz - globalqss                                           *
 * Sponsored by FH                                                     *
 **********************************************************************/

package org.idempiere.process;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.compiere.model.MProcessPara;
import org.compiere.model.MSequence;
import org.compiere.model.MTable;
import org.compiere.model.MTree;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Trx;
import org.compiere.util.Util;

@org.adempiere.base.annotation.Process
public class MigraID extends SvrProcess {

	// Process to change the ID of a record in the whole database

	private int p_AD_Table_ID = -1;
	private int p_ID_From = -1;
	private int p_ID_To = -1;
	private String p_UUID_From = null;
	private String p_UUID_To = null;

	@Override
	protected void prepare() {
		//
		for (ProcessInfoParameter para : getParameter()) {
			String name = para.getParameterName();
			if ("AD_Table_ID".equals(name)) {
				p_AD_Table_ID  = para.getParameterAsInt();
			} else if ("Record_ID".equals(name)) {
				p_ID_From = para.getParameterAsInt();
			} else if ("To_Record_ID".equals(name)) {
				p_ID_To = para.getParameterAsInt();
			} else if ("Source_UUID".equals(name)) {
				p_UUID_From = para.getParameterAsString();
			} else if ("Target_UUID".equals(name)) {
				p_UUID_To = para.getParameterAsString();
			} else {
				MProcessPara.validateUnknownParameter(getProcessInfo().getAD_Process_ID(), para);
			}
		}
	}

	@Override
	protected String doIt() throws Exception {
		if (p_ID_From <= 0 && Util.isEmpty(p_UUID_From)) {
			throw new AdempiereUserError("Fill Record ID or UUID to convert");
		}
		if (p_ID_From > 0 && ! Util.isEmpty(p_UUID_From)) {
			throw new AdempiereUserError("Record ID and UUID are excluyent, just one can be converted at the same time");
		}
		if (p_ID_From > 0 && p_ID_From == p_ID_To) {
			throw new AdempiereUserError("Same ID");
		}
		if (! Util.isEmpty(p_UUID_From) && p_UUID_From.equals(p_UUID_To)) {
			throw new AdempiereUserError("Same UUID");
		}
		MTable table = MTable.get(getCtx(), p_AD_Table_ID, get_TrxName());
		String tableName = table.getTableName();
		String msg = "";

		if (! Util.isEmpty(p_UUID_From)) {
			String uuidCol = PO.getUUIDColumnName(tableName);
			if (Util.isEmpty(p_UUID_To)) {
				p_UUID_To = UUID.randomUUID().toString();
			}
			// convert UUID
			StringBuilder updUUIDSB = new StringBuilder()
					.append("UPDATE ").append(tableName)
					.append(" SET ").append(uuidCol).append("=?")
					.append(" WHERE ").append(uuidCol).append("=?");
			int cnt = DB.executeUpdateEx(updUUIDSB.toString(), new Object[] {p_UUID_To, p_UUID_From}, get_TrxName());
			if (cnt <= 0) {
				msg = "@Error@: UUID " + p_UUID_From + " not found on table " + tableName;
			} else {
				int id = -1;
				msg = "UUID changed on table " + tableName + " from " + p_UUID_From + " to " + p_UUID_To;
				if (table.isIDKeyTable()) {
					StringBuilder sqlSB = new StringBuilder()
							.append("SELECT  ").append(tableName).append("_ID")
							.append(" FROM ").append(tableName)
							.append(" WHERE ").append(uuidCol).append("=?");
					id = DB.getSQLValueEx(get_TrxName(), sqlSB.toString(), p_UUID_To);
				}
				addBufferLog(0, null, null, msg, p_AD_Table_ID, id);
				msg = "@OK@";
				// migrateReferenceUU(tableName);
				migrateChildren(tableName, false);
				// migrateRecordUU();
				// migrateAD_PreferenceUU(idCol);
				// migrateTreesUU(tableName);
				// TODO: implement migration for SingleSelectionGrid, MultipleSelectionGrid, ChosenMultipleSelectionTable, ChosenMultipleSelectionSearch
			}
		} else {
			boolean seqCheck = false;
			String idCol = tableName + "_ID";
			if (p_ID_To <= 0) {
				p_ID_To = DB.getNextID(getAD_Client_ID(), tableName, get_TrxName());
			} else {
				StringBuilder sqlMaxSB = new StringBuilder()
						.append("SELECT  MAX(").append(tableName).append("_ID)")
						.append(" FROM ").append(tableName);
				int maxID = DB.getSQLValueEx(get_TrxName(), sqlMaxSB.toString());
				if (p_ID_To > maxID) {
					seqCheck = true;
				}
			}
			// convert ID
			int cnt = updID(tableName, idCol, true);
			if (cnt <= 0) {
				msg = "@Error@: ID " + p_ID_From + " not found on table " + tableName;
			} else {
				msg = "ID changed on table " + tableName + " from " + p_ID_From + " to " + p_ID_To;
				addBufferLog(p_ID_From, null, null, msg, p_AD_Table_ID, p_ID_To);
				msg = "@OK@";
				migrateReference(tableName);
				migrateChildren(tableName, true);
				migrateRecordID();
				migrateAD_Preference(idCol);
				migrateTrees(tableName);
				if ("C_DocType_ID".equals(idCol)) {
					// special preference C_DocTypeTarget_ID
					migrateAD_Preference("C_DocTypeTarget_ID");
				}
				// TODO: implement migration for SingleSelectionGrid, MultipleSelectionGrid, ChosenMultipleSelectionTable, ChosenMultipleSelectionSearch

				if (seqCheck) {
					MSequence seq = MSequence.get(getCtx(), tableName, get_TrxName());
					if (seq != null) {
						seq.validateTableIDValue(get_TrxName()); // ignore output messages
					}
				}
			}
		}

		/* Force showing error in commit - for example when violating foreign keys */
		Trx.get(get_TrxName(), false).commit(true);

		return msg;
	}

	/**
	 * Update key on table
	 * @param tableName - table
	 * @param idCol - id column
	 * @param idKey - true when migrating ID keys, false when migrating UUID keys
	 * @return count of records updated
	 */
	private int updID(String tableName, String idCol, boolean idKey) {
		StringBuilder updIDSB = new StringBuilder()
				.append("UPDATE ").append(tableName)
				.append(" SET ").append(idCol).append("=?")
				.append(" WHERE ").append(idCol).append("=?");
		int cnt;
		if (idKey)
			cnt = DB.executeUpdateEx(updIDSB.toString(), new Object[] {p_ID_To, p_ID_From}, get_TrxName());
		else
			cnt = DB.executeUpdateEx(updIDSB.toString(), new Object[] {p_UUID_To, p_UUID_From}, get_TrxName());
		return cnt;
	}

	private void migrateReference(String tableName) {
		// Special cases with direct reference
		int refID = -1;
		switch (tableName) {
		case "C_Location"             : refID = DisplayType.Location;   break;
		case "C_ValidCombination"     : refID = DisplayType.Account;    break;
		case "M_Locator"              : refID = DisplayType.Locator;    break;
		case "M_AttributeSetInstance" : refID = DisplayType.PAttribute; break;
		case "S_ResourceAssignment"   : refID = DisplayType.Assignment; break;
		case "AD_Image"               : refID = DisplayType.Image;      break;
		case "AD_Color"               : refID = DisplayType.Color;      break;
		case "AD_Chart"               : refID = DisplayType.Chart;      break;
		}
		if (refID > 0) {
			final String selRef = ""
					+ "SELECT t.TableName, c.ColumnName "
					+ "FROM AD_Table t "
					+ "JOIN AD_Column c ON (t.AD_Table_ID=c.AD_Table_ID) "
					+ "WHERE t.IsView='N' AND t.IsActive='Y' AND c.IsActive='Y' AND c.ColumnSQL IS NULL AND c.AD_Reference_ID=? "
					+ "ORDER BY t.TableName, c.ColumnName";
			List<List<Object>> rows = DB.getSQLArrayObjectsEx(get_TrxName(), selRef, refID);
			if (rows != null && rows.size() > 0) {
				for (List<Object> row : rows) {
					String tableRef = (String) row.get(0);
					String columnRef = (String) row.get(1);
					int cnt = updID(tableRef, columnRef, true);
					if (cnt > 0) {
						String msg = cnt + " reference records updated in " + tableRef + "." + columnRef;
						addBufferLog(p_ID_From, null, null, msg, 0, 0);
					}
				}
			}
		}

	}

	/**
	 * Migrate foreign keys for tableName
	 * @param tableName - name of parent table to migrate children foreign keys
	 * @param idKey - true when migrating ID keys, false when migrating UUID keys
	 */
	private void migrateChildren(String tableName, boolean idKey) {
		final String sqlFK = ""
				+ "SELECT t.TableName, c.ColumnName "
				+ "FROM   AD_Table t, AD_Column c, AD_Reference r " 
				+ "WHERE  t.AD_Table_ID = c.AD_Table_ID "
				+ "       AND t.IsActive = 'Y' AND t.IsView = 'N' " 
				+ "       AND c.IsActive = 'Y' AND c.ColumnSql IS NULL "
				+ "       AND c.AD_Reference_ID = r.AD_Reference_ID "
				+ "       AND ( c.AD_Reference_ID=? "
				+ "              OR ( c.AD_Reference_ID=? "
				+ "                   AND c.AD_Reference_Value_ID IS NULL ) ) "
				+ "       AND UPPER(c.ColumnName) = UPPER(?) "
				+ "UNION "
				+ "SELECT t.TableName, c.ColumnName "
				+ "FROM   AD_Table t, AD_Column c, AD_Reference r, AD_Ref_Table rt, AD_Table tr "
				+ "WHERE  t.AD_Table_ID = c.AD_Table_ID "
				+ "       AND t.IsActive = 'Y' AND t.IsView = 'N' " 
				+ "       AND c.IsActive = 'Y' AND c.ColumnSql IS NULL "
				+ "       AND c.AD_Reference_ID = r.AD_Reference_ID "
				+ "       AND ( c.AD_Reference_ID=? "
				+ "              OR ( c.AD_Reference_ID=? "
				+ "                   AND c.AD_Reference_Value_ID IS NOT NULL ) ) "
				+ "       AND c.AD_Reference_Value_ID = rt.AD_Reference_ID "
				+ "       AND rt.AD_Table_ID = tr.AD_Table_ID "
				+ "       AND UPPER(tr.TableName) = UPPER(?) "
				+ "ORDER  BY 1, 2";
		int tableDirRefId;
		int searchRefId;
		int tableRefId;
		String foreignColName;
		if (idKey) {
			tableDirRefId = DisplayType.TableDir;
			searchRefId = DisplayType.Search;
			tableRefId = DisplayType.Table;
			foreignColName = tableName + "_ID";
		} else {
			tableDirRefId = DisplayType.TableDirUU;
			searchRefId = DisplayType.SearchUU;
			tableRefId = DisplayType.TableUU;
			foreignColName = PO.getUUIDColumnName(tableName);
		}
		List<List<Object>> rows = DB.getSQLArrayObjectsEx(get_TrxName(), sqlFK, tableDirRefId, searchRefId, foreignColName, tableRefId, searchRefId, tableName);
		if (rows != null && rows.size() > 0) {
			for (List<Object> row : rows) {
				String tableRef = (String) row.get(0);
				String columnRef = (String) row.get(1);
				// Special cases EntityType and AD_Language
				if ("EntityType".equals(columnRef) || "AD_Language".equals(columnRef)) {
					continue;
				}
				int cnt = updID(tableRef, columnRef, idKey);
				if (cnt > 0) {
					String msg = cnt + " children records updated in " + tableRef + "." + columnRef;
					addBufferLog(p_ID_From, null, null, msg, 0, 0);
				}
			}
		}
		// Special case for C_BPartner.AD_OrgBP_ID defined as Button in dictionary
		if (idKey && "AD_Org".equalsIgnoreCase(tableName)) {
			String tableRef = "C_BPartner";
			String columnRef = "AD_OrgBP_ID";
			int cnt = updID(tableRef, columnRef, idKey);
			if (cnt > 0) {
				String msg = cnt + " children records updated in " + tableRef + "." + columnRef;
				addBufferLog(p_ID_From, null, null, msg, 0, 0);
			}
		}
	}

	private void migrateRecordID() {
		final String whereClause = "IsView='N' AND IsActive='Y'" + 
				" AND EXISTS (SELECT 1 FROM AD_Column ct WHERE ct.AD_Table_ID=AD_Table.AD_Table_ID AND ct.ColumnName='AD_Table_ID' AND ct.ColumnSQL IS NULL AND ct.IsActive='Y')" + 
				" AND EXISTS (SELECT 1 FROM AD_Column cr WHERE cr.AD_Table_ID=AD_Table.AD_Table_ID AND cr.ColumnName='Record_ID'   AND cr.ColumnSQL IS NULL AND cr.IsActive='Y')";
		List<MTable> tablesWithRecordID = new Query(getCtx(), "AD_Table", whereClause, get_TrxName())
				.setOrderBy("TableName")
				.list();
		for (MTable table : tablesWithRecordID) {
			String tableName = table.getTableName();
			StringBuilder updRISB = new StringBuilder()
					.append("UPDATE ").append(tableName)
					.append(" SET Record_ID=?")
					.append(" WHERE Record_ID=? AND AD_Table_ID=?");
			int cnt = DB.executeUpdateEx(updRISB.toString(), new Object[] {p_ID_To, p_ID_From, p_AD_Table_ID}, get_TrxName());
			if (cnt > 0) {
				String msg = cnt + " weak reference records updated in " + tableName;
				addBufferLog(p_ID_From, null, null, msg, 0, 0);
			}
		}
	}

	private void migrateAD_Preference(String columnName) {
		final String updPref = "UPDATE AD_Preference SET Value=? WHERE Value=? AND Attribute=?";
		int cnt = DB.executeUpdateEx(updPref, new Object[] {String.valueOf(p_ID_To), String.valueOf(p_ID_From), columnName}, get_TrxName());
		if (cnt > 0) {
			String msg = cnt + " preference records updated in AD_Preference for " + columnName;
			addBufferLog(p_ID_From, null, null, msg, 0, 0);
		}
	}

	private void migrateTrees(String tableName) {
		switch (tableName) {
		case "AD_Menu":
			migraTree("AD_TreeBar", MTree.TREETYPE_Menu);
			migraTree("AD_TreeNodeMM", MTree.TREETYPE_Menu);
			break;
		case "C_BPartner":
			migraTree("AD_TreeNodeBP", MTree.TREETYPE_BPartner);
			break;
		case "CM_Container":
			migraTree("AD_TreeNodeCMC", MTree.TREETYPE_CMContainer);
			break;
		case "CM_Media":
			migraTree("AD_TreeNodeCMM", MTree.TREETYPE_CMMedia);
			break;
		case "CM_CStage":
			migraTree("AD_TreeNodeCMS", MTree.TREETYPE_CMContainerStage);
			break;
		case "CM_Template":
			migraTree("AD_TreeNodeCMT", MTree.TREETYPE_CMTemplate);
			break;
		case "M_Product":
			migraTree("AD_TreeNodePR", MTree.TREETYPE_Product);
			break;
		case "C_ElementValue":
			migraTree("AD_TreeNodeU1", MTree.TREETYPE_User1);
			migraTree("AD_TreeNodeU2", MTree.TREETYPE_User2);
			migraTree("AD_TreeNodeU3", MTree.TREETYPE_User3);
			migraTree("AD_TreeNodeU4", MTree.TREETYPE_User4);
			break;
		case "AD_Org":
			migraTree("AD_TreeNode", MTree.TREETYPE_Organization);
			break;
		case "M_Product_Category":
			migraTree("AD_TreeNode", MTree.TREETYPE_ProductCategory);
			break;
		case "M_BOM":
			migraTree("AD_TreeNode", MTree.TREETYPE_BoM);
			break;
		case "C_Campaign":
			migraTree("AD_TreeNode", MTree.TREETYPE_Campaign);
			break;
		case "C_Project":
			migraTree("AD_TreeNode", MTree.TREETYPE_Project);
			break;
		case "C_Activity":
			migraTree("AD_TreeNode", MTree.TREETYPE_Activity);
			break;
		case "C_SalesRegion":
			migraTree("AD_TreeNode", MTree.TREETYPE_SalesRegion);
			break;
		}
		migraTree("AD_TreeNode", MTree.TREETYPE_CustomTable);
	}

	private void migraTree(String menuTable, String treeType) {
		List<String> columns = new ArrayList<String>();
		columns.add("Node_ID");
		if (! "AD_TreeBar".equalsIgnoreCase(menuTable)) {
			columns.add("Parent_ID");
		}
		for (String col : columns) {
			StringBuilder sqlUpdTreeSB = new StringBuilder()
					.append("UPDATE ").append(menuTable)
					.append(" SET ").append(col).append("=? WHERE ").append(col).append("=? AND AD_Tree_ID IN (SELECT AD_Tree_ID FROM AD_Tree WHERE TreeType=?");
			if (MTree.TREETYPE_CustomTable.equals(treeType)) {
				sqlUpdTreeSB.append(" AND AD_Table_ID=").append(p_AD_Table_ID);
			}
			sqlUpdTreeSB.append(")");
			int cnt = DB.executeUpdateEx(sqlUpdTreeSB.toString(), new Object[] {p_ID_To, p_ID_From, treeType}, get_TrxName());
			if (cnt > 0) {
				String msg = cnt + " tree records updated in " + menuTable + "." + col;
				addBufferLog(p_ID_From, null, null, msg, 0, 0);
			}
		}
	}

}
