/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.dao.aaf.cass;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;

import com.att.authz.env.AuthzTrans;
import com.att.authz.layer.Result;
import com.att.dao.Bytification;
import com.att.dao.CIDAO;
import com.att.dao.Cached;
import com.att.dao.CassDAOImpl;
import com.att.dao.Loader;
import com.att.dao.Streamer;
import com.att.inno.env.APIException;
import com.att.inno.env.util.Chrono;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;

/**
 * CredDAO manages credentials. 
 * Date: 7/19/13
 */
public class CredDAO extends CassDAOImpl<AuthzTrans,CredDAO.Data> {
    public static final String TABLE = "cred";
    public static final int CACHE_SEG = 0x40; // yields segment 0x0-0x3F
	public static final int RAW = -1;
    public static final int BASIC_AUTH = 1;
    public static final int BASIC_AUTH_SHA256 = 2;
    public static final int CERT_SHA256_RSA =200;
    
    private HistoryDAO historyDAO;
	private CIDAO<AuthzTrans> infoDAO;
	private PSInfo psNS;
	private PSInfo psID;
	
    public CredDAO(AuthzTrans trans, Cluster cluster, String keyspace) throws APIException, IOException {
        super(trans, CredDAO.class.getSimpleName(),cluster, keyspace, Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
        init(trans);
    }

    public CredDAO(AuthzTrans trans, HistoryDAO hDao, CacheInfoDAO ciDao) throws APIException, IOException {
        super(trans, CredDAO.class.getSimpleName(),hDao, Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
        historyDAO = hDao;
        infoDAO = ciDao;
        init(trans);
    }

    public static final int KEYLIMIT = 3;
	public static class Data extends CacheableData implements Bytification {
    	
		public String       			id;
        public Integer      			type;
        public Date      				expires;
        public Integer					other;
		public String					ns;
		public String					notes;
        public ByteBuffer				cred;  //   this is a blob in cassandra


        @Override
		public int[] invalidate(Cached<?,?> cache) {
        	return new int[] {
        		seg(cache,id) // cache is for all entities
        	};
		}
        
		@Override
		public ByteBuffer bytify() throws IOException {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			CredLoader.deflt.marshal(this,new DataOutputStream(baos));
			return ByteBuffer.wrap(baos.toByteArray());
		}
		
		@Override
		public void reconstitute(ByteBuffer bb) throws IOException {
			CredLoader.deflt.unmarshal(this, toDIS(bb));
		}

		public String toString() {
			return id + ' ' + type + ' ' + Chrono.dateTime(expires);
		}
    }

    private static class CredLoader extends Loader<Data> implements Streamer<Data>{
		public static final int MAGIC=153323443;
    	public static final int VERSION=1;
    	public static final int BUFF_SIZE=48; // Note: 

    	public static final CredLoader deflt = new CredLoader(KEYLIMIT);
    	public CredLoader(int keylimit) {
            super(keylimit);
        }

    	@Override
        public Data load(Data data, Row row) {
            data.id = row.getString(0);
            data.type = row.getInt(1);    // NOTE: in datastax driver,  If the int value is NULL, 0 is returned!
            data.expires = row.getDate(2);
            data.other = row.getInt(3);
            data.ns = row.getString(4);     
            data.notes = row.getString(5);
            data.cred = row.getBytesUnsafe(6);            
            return data;
        }

        @Override
        protected void key(Data data, int _idx, Object[] obj) {
	    int idx = _idx;

            obj[idx] = data.id;
            obj[++idx] = data.type;
            obj[++idx] = data.expires;
        }

        @Override
        protected void body(Data data, int idx, Object[] obj) {
            int i;
            obj[i=idx] = data.other;
            obj[++i] = data.ns;
            obj[++i] = data.notes;
            obj[++i] = data.cred;
        }

		@Override
		public void marshal(Data data, DataOutputStream os) throws IOException {
			writeHeader(os,MAGIC,VERSION);
			writeString(os, data.id);
			os.writeInt(data.type);	
			os.writeLong(data.expires==null?-1:data.expires.getTime());
			os.writeInt(data.other==null?0:data.other);
			writeString(os, data.ns);
			writeString(os, data.notes);
			if(data.cred==null) {
				os.writeInt(-1);
			} else {
				int l = data.cred.limit()-data.cred.position();
				os.writeInt(l);
				os.write(data.cred.array(),data.cred.position(),l);
			}
		}

		@Override
		public void unmarshal(Data data, DataInputStream is) throws IOException {
			/*int version = */readHeader(is,MAGIC,VERSION);
			// If Version Changes between Production runs, you'll need to do a switch Statement, and adequately read in fields
			byte[] buff = new byte[BUFF_SIZE];
			data.id = readString(is,buff);
			data.type = is.readInt();
			
			long l = is.readLong();
			data.expires = l<0?null:new Date(l);
			data.other = is.readInt();
			data.ns = readString(is,buff);
			data.notes = readString(is,buff);
			
			int i = is.readInt();
			if(i<0) {
				data.cred=null;
			} else {
				byte[] bytes = new byte[i]; // a bit dangerous, but lessened because of all the previous sized data reads
				is.read(bytes);
				data.cred = ByteBuffer.wrap(bytes);
			}
		}
    }

    private void init(AuthzTrans trans) throws APIException, IOException {
        // Set up sub-DAOs
        if(historyDAO==null) {
        	historyDAO = new HistoryDAO(trans,this);
        }
		if(infoDAO==null) {
			infoDAO = new CacheInfoDAO(trans,this);
		}
		

		String[] helpers = setCRUD(trans, TABLE, Data.class, CredLoader.deflt);
		
		psNS = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE +
				" WHERE ns = ?", CredLoader.deflt,readConsistency);
		
		psID = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE +
				" WHERE id = ?", CredLoader.deflt,readConsistency);
    }
    
	public Result<List<Data>> readNS(AuthzTrans trans, String ns) {
		return psNS.read(trans, R_TEXT, new Object[]{ns});
	}
	
	public Result<List<Data>> readID(AuthzTrans trans, String id) {
		return psID.read(trans, R_TEXT, new Object[]{id});
	}
	
    /**
     * Log Modification statements to History
     *
     * @param modified        which CRUD action was done
     * @param data            entity data that needs a log entry
     * @param overrideMessage if this is specified, we use it rather than crafting a history message based on data
     */
    @Override
    protected void wasModified(AuthzTrans trans, CRUD modified, Data data, String ... override) {
    	boolean memo = override.length>0 && override[0]!=null;
    	boolean subject = override.length>1 && override[1]!=null;

        HistoryDAO.Data hd = HistoryDAO.newInitedData();
        hd.user = trans.user();
        hd.action = modified.name();
        hd.target = TABLE;
        hd.subject = subject?override[1]: data.id;
        hd.memo = memo
                ? String.format("%s by %s", override[0], hd.user)
                : (modified.name() + "d credential for " + data.id);
        // Detail?
   		if(modified==CRUD.delete) {
        			try {
        				hd.reconstruct = data.bytify();
        			} catch (IOException e) {
        				trans.error().log(e,"Could not serialize CredDAO.Data");
        			}
        		}

        if(historyDAO.create(trans, hd).status!=Status.OK) {
        	trans.error().log("Cannot log to History");
        }
        if(infoDAO.touch(trans, TABLE,data.invalidate(cache)).status!=Status.OK) {
        	trans.error().log("Cannot touch Cred");
        }
    }
}
