/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.dao.aaf.cass;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.att.authz.env.AuthzTrans;
import com.att.authz.layer.Result;
import com.att.dao.Bytification;
import com.att.dao.Cached;
import com.att.dao.CassAccess;
import com.att.dao.CassDAOImpl;
import com.att.dao.Loader;
import com.att.dao.Streamer;
import com.att.inno.env.APIException;
import com.att.inno.env.Env;
import com.att.inno.env.TimeTaken;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.exceptions.DriverException;

/**
 * NsDAO
 * 
 * Data Access Object for Namespace Data
 *
 */
public class NsDAO extends CassDAOImpl<AuthzTrans,NsDAO.Data> {
	public static final String TABLE = "ns";
	public static final String TABLE_ATTRIB = "ns_attrib";
    public static final int CACHE_SEG = 0x40; // yields segment 0x0-0x3F
    public static final int ROOT = 1;
    public static final int COMPANY=2;
    public static final int APP = 3;

	private static final String BEGIN_BATCH = "BEGIN BATCH\n";
	private static final String APPLY_BATCH = "APPLY BATCH;\n";
	private static final String SQSCCR = "';\n";
	private static final String SQCSQ = "','";
    
	private HistoryDAO historyDAO;
	private CacheInfoDAO infoDAO;
	private PSInfo psNS;

	public NsDAO(AuthzTrans trans, Cluster cluster, String keyspace) throws APIException, IOException {
		super(trans, NsDAO.class.getSimpleName(),cluster,keyspace,Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
		init(trans);
	}

	public NsDAO(AuthzTrans trans, HistoryDAO hDAO, CacheInfoDAO iDAO) throws APIException, IOException {
		super(trans, NsDAO.class.getSimpleName(),hDAO,Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
		historyDAO=hDAO;
		infoDAO = iDAO;
		init(trans);
	}


    //////////////////////////////////////////
    // Data Definition, matches Cassandra DM
    //////////////////////////////////////////
    private static final int KEYLIMIT = 1;
    /**
     * Data class that matches the Cassandra Table "role"
     * 
     */
	public static class Data extends CacheableData implements Bytification {
		public String		      name;
		public int			      type;
		public String			  description;
		public String			  parent;
		public Map<String,String> attrib;

//		////////////////////////////////////////
//        // Getters
		public Map<String,String> attrib(boolean mutable) {
			if (attrib == null) {
				attrib = new HashMap<String,String>();
			} else if (mutable && !(attrib instanceof HashMap)) {
				attrib = new HashMap<String,String>(attrib);
			}
			return attrib;
		}

		@Override
		public int[] invalidate(Cached<?,?> cache) {
			return new int[] {
				seg(cache,name)
			};
		}

		public NsSplit split(String name) {
			return new NsSplit(this,name);
		}

		@Override
		public ByteBuffer bytify() throws IOException {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			NSLoader.deflt.marshal(this,new DataOutputStream(baos));
			return ByteBuffer.wrap(baos.toByteArray());
		}
		
		@Override
		public void reconstitute(ByteBuffer bb) throws IOException {
			NSLoader.deflt.unmarshal(this,toDIS(bb));
		}
		
		@Override
		public String toString() {
			return name;
		}
		
    }
    
    private void init(AuthzTrans trans) throws APIException, IOException {
        // Set up sub-DAOs
        if(historyDAO==null) {
	    historyDAO = new HistoryDAO(trans, this);
	}
        if(infoDAO==null) {
	    infoDAO = new CacheInfoDAO(trans,this);
	}

		String[] helpers = setCRUD(trans, TABLE, Data.class, NSLoader.deflt,4/*need to skip attrib */);
		
		psNS = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE +
				" WHERE parent = ?", new NSLoader(1),readConsistency);

	}
	
    private static final class NSLoader extends Loader<Data> implements Streamer<Data> {
		public static final int MAGIC=250935515;
    	public static final int VERSION=1;
    	public static final int BUFF_SIZE=48;

    	public static final NSLoader deflt = new NSLoader(KEYLIMIT);
    	
		public NSLoader(int keylimit) {
			super(keylimit);
		}

		@Override
		public Data load(Data data, Row row) {
			// Int more efficient
			data.name = row.getString(0);
			data.type = row.getInt(1);
			data.description = row.getString(2);
			data.parent = row.getString(3);
			return data;
		}

		@Override
		protected void key(Data data, int idx, Object[] obj) {
			obj[idx]=data.name;
		}

		@Override
		protected void body(Data data, int _idx, Object[] obj) {
		    	int idx = _idx;

			obj[idx]=data.type;
			obj[++idx]=data.description;
			obj[++idx]=data.parent;
		}
		
		@Override
		public void marshal(Data data, DataOutputStream os) throws IOException {
			writeHeader(os,MAGIC,VERSION);
			writeString(os, data.name);
			os.writeInt(data.type);
			writeString(os,data.description);
			writeString(os,data.parent);
			if(data.attrib==null) {
				os.writeInt(-1);
			} else {
				os.writeInt(data.attrib.size());
				for(Entry<String, String> es : data.attrib(false).entrySet()) {
					writeString(os,es.getKey());
					writeString(os,es.getValue());
				}
			}
		}

		@Override
		public void unmarshal(Data data, DataInputStream is) throws IOException {
			/*int version = */readHeader(is,MAGIC,VERSION);
			// If Version Changes between Production runs, you'll need to do a switch Statement, and adequately read in fields
			
			byte[] buff = new byte[BUFF_SIZE];
			data.name = readString(is, buff);
			data.type = is.readInt();
			data.description = readString(is,buff);
			data.parent = readString(is,buff);
			int count = is.readInt();
			if(count>0) {
				Map<String, String> da = data.attrib(true);
				for(int i=0;i<count;++i) {
					da.put(readString(is,buff), readString(is,buff));
				}
			}
		}

    }
    
	@Override
	public Result<Data> create(AuthzTrans trans, Data data) {
		String ns = data.name;
		// Ensure Parent is set
		int ldot = ns.lastIndexOf('.');
		data.parent=ldot<0?".":ns.substring(0,ldot);

		// insert Attributes
		StringBuilder stmt = new StringBuilder();
		stmt.append(BEGIN_BATCH);
		attribInsertStmts(stmt, data);
		stmt.append(APPLY_BATCH);
		try {
			getSession(trans).execute(stmt.toString());
//// TEST CODE for Exception				
//			boolean force = true; 
//			if(force) {
//				throw new com.datastax.driver.core.exceptions.NoHostAvailableException(new HashMap<InetSocketAddress,Throwable>());
////				throw new com.datastax.driver.core.exceptions.AuthenticationException(new InetSocketAddress(9999),"Sample Message");
//			}
////END TEST CODE

		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			trans.info().log(stmt);
			return Result.err(Result.ERR_Backend, "Backend Access");
		}
		return super.create(trans, data);
	}

	@Override
	public Result<Void> update(AuthzTrans trans, Data data) {
		String ns = data.name;
		// Ensure Parent is set
		int ldot = ns.lastIndexOf('.');
		data.parent=ldot<0?".":ns.substring(0,ldot);

		StringBuilder stmt = new StringBuilder();
		stmt.append(BEGIN_BATCH);
		try {
			Map<String, String> localAttr = data.attrib;
			Result<Map<String, String>> rremoteAttr = readAttribByNS(trans,ns);
			if(rremoteAttr.notOK()) {
				return Result.err(rremoteAttr);
			}
			// update Attributes
			String str;
			for(Entry<String, String> es : localAttr.entrySet()) {
				str = rremoteAttr.value.get(es.getKey());
				if(str==null || !str.equals(es.getValue())) {
					attribInsertStmt(stmt, ns, es.getKey(),es.getValue());
				}
			}
			
			// No point in deleting... insert overwrites...
//			for(Entry<String, String> es : remoteAttr.entrySet()) {
//				str = localAttr.get(es.getKey());
//				if(str==null || !str.equals(es.getValue())) {
//					attribDeleteStmt(stmt, ns, es.getKey());
//				}
//			}
			if(stmt.length()>BEGIN_BATCH.length()) {
				stmt.append(APPLY_BATCH);
				getSession(trans).execute(stmt.toString());
			}
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			trans.info().log(stmt);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}

		return super.update(trans,data);
	}

	/* (non-Javadoc)
	 * @see com.att.dao.CassDAOImpl#read(com.att.inno.env.TransStore, java.lang.Object)
	 */
	@Override
	public Result<List<Data>> read(AuthzTrans trans, Data data) {
		Result<List<Data>> rld = super.read(trans, data);
		
		if(rld.isOKhasData()) {
			for(Data d : rld.value) {
				// Note: Map is null at this point, save time/mem by assignment
				Result<Map<String, String>> rabn = readAttribByNS(trans,d.name);
				if(rabn.isOK()) {
					d.attrib = rabn.value;
				} else {
					return Result.err(rabn);
				}
			}
		}
		return rld;
	}

	/* (non-Javadoc)
	 * @see com.att.dao.CassDAOImpl#read(com.att.inno.env.TransStore, java.lang.Object[])
	 */
	@Override
	public Result<List<Data>> read(AuthzTrans trans, Object... key) {
		Result<List<Data>> rld = super.read(trans, key);

		if(rld.isOKhasData()) {
			for(Data d : rld.value) {
				// Note: Map is null at this point, save time/mem by assignment
				Result<Map<String, String>> rabn = readAttribByNS(trans,d.name);
				if(rabn.isOK()) {
					d.attrib = rabn.value;
				} else {
					return Result.err(rabn);
				}
			}
		}
		return rld;
	}

	@Override
	public Result<Void> delete(AuthzTrans trans, Data data, boolean reread) {
		TimeTaken tt = trans.start("Delete NS Attributes " + data.name, Env.REMOTE);
		try {
			StringBuilder stmt = new StringBuilder();
			attribDeleteAllStmt(stmt, data);
			try {
				getSession(trans).execute(stmt.toString());
			} catch (DriverException | APIException | IOException e) {
				reportPerhapsReset(trans,e);
				trans.info().log(stmt);
				return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
			}
		} finally {
			tt.done();
		}
		return super.delete(trans, data, reread);

	}
    
	public Result<Map<String,String>> readAttribByNS(AuthzTrans trans, String ns) {
		Map<String,String> map = new HashMap<String,String>();
		TimeTaken tt = trans.start("readAttribByNS " + ns, Env.REMOTE);
		try {
			ResultSet rs = getSession(trans).execute("SELECT key,value FROM " 
					+ TABLE_ATTRIB 
					+ " WHERE ns='"
					+ ns
					+ "';");
			
			for(Iterator<Row> iter = rs.iterator();iter.hasNext(); ) {
				Row r = iter.next();
				map.put(r.getString(0), r.getString(1));
			}
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		} finally {
			tt.done();
		}
		return Result.ok(map);
	}

	public Result<Set<String>> readNsByAttrib(AuthzTrans trans, String key) {
		Set<String> set = new HashSet<String>();
		TimeTaken tt = trans.start("readNsBykey " + key, Env.REMOTE);
		try {
			ResultSet rs = getSession(trans).execute("SELECT ns FROM " 
				+ TABLE_ATTRIB 
				+ " WHERE key='"
				+ key
				+ "';");
		
			for(Iterator<Row> iter = rs.iterator();iter.hasNext(); ) {
				Row r = iter.next();
				set.add(r.getString(0));
			}
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		} finally {
			tt.done();
		}
		return Result.ok(set);
	}

	public Result<Void> attribAdd(AuthzTrans trans, String ns, String key, String value) {
		try {
			getSession(trans).execute(attribInsertStmt(new StringBuilder(),ns,key,value).toString());
			return Result.ok();
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}
	}
	
	private StringBuilder attribInsertStmt(StringBuilder sb, String ns, String key, String value) {
		sb.append("INSERT INTO ");
		sb.append(TABLE_ATTRIB);
		sb.append(" (ns,key,value) VALUES ('");
		sb.append(ns);
		sb.append(SQCSQ);
		sb.append(key);
		sb.append(SQCSQ);
		sb.append(value);
		sb.append("');");
		return sb;
	}
	
	public Result<Void> attribRemove(AuthzTrans trans, String ns, String key) {
		try {
			getSession(trans).execute(attribDeleteStmt(new StringBuilder(),ns,key).toString());
			return Result.ok();
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}
	}
	
	private StringBuilder attribDeleteStmt(StringBuilder stmt, String ns, String key) {
		stmt.append("DELETE FROM ");
		stmt.append(TABLE_ATTRIB);
		stmt.append(" WHERE ns='");
		stmt.append(ns);
		stmt.append("' AND key='");
		stmt.append(key);
		stmt.append("';");
		return stmt;
	}
	
	private void attribDeleteAllStmt(StringBuilder stmt, Data data) {
		stmt.append("  DELETE FROM ");
		stmt.append(TABLE_ATTRIB);
		stmt.append(" WHERE ns='");
		stmt.append(data.name);
		stmt.append(SQSCCR);
	}

	private void attribInsertStmts(StringBuilder stmt, Data data) {
		// INSERT new Attrib
		for(Entry<String,String> es : data.attrib(false).entrySet() ) {
			stmt.append("  ");
			attribInsertStmt(stmt,data.name,es.getKey(),es.getValue());
		}
	}

	/**
	 * Add description to Namespace
	 * @param trans
	 * @param ns
	 * @param description
	 * @return
	 */
	public Result<Void> addDescription(AuthzTrans trans, String ns, String description) {
		try {
			getSession(trans).execute(UPDATE_SP + TABLE + " SET description = '" 
				+ description + "' WHERE name = '" + ns + "';");
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}

		Data data = new Data();
		data.name=ns;
		wasModified(trans, CRUD.update, data, "Added description " + description + " to namespace " + ns, null );
		return Result.ok();
	}

	public Result<List<Data>> getChildren(AuthzTrans trans, String parent) {
		return psNS.read(trans, R_TEXT, new Object[]{parent});
	}
		

    /**
     * Log Modification statements to History
     * 
     * @param modified           which CRUD action was done
     * @param data               entity data that needs a log entry
     * @param overrideMessage    if this is specified, we use it rather than crafting a history message based on data
     */
    @Override
    protected void wasModified(AuthzTrans trans, CRUD modified, Data data, String ... override) {
    	boolean memo = override.length>0 && override[0]!=null;
    	boolean subject = override.length>1 && override[1]!=null;

        //TODO Must log history
        HistoryDAO.Data hd = HistoryDAO.newInitedData();
        hd.user = trans.user();
        hd.action = modified.name();
        hd.target = TABLE;
        hd.subject = subject ? override[1] : data.name;
        hd.memo = memo ? override[0] : (data.name + " was "  + modified.name() + 'd' );
		if(modified==CRUD.delete) {
			try {
				hd.reconstruct = data.bytify();
			} catch (IOException e) {
				trans.error().log(e,"Could not serialize NsDAO.Data");
			}
		}

        if(historyDAO.create(trans, hd).status!=Status.OK) {
	    trans.error().log("Cannot log to History");
	}
        if(infoDAO.touch(trans, TABLE,data.invalidate(cache)).notOK()) {
	    trans.error().log("Cannot touch CacheInfo");
	}
    }

}