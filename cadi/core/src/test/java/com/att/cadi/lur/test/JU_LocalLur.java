/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.cadi.lur.test;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.att.cadi.CredVal.Type;
import com.att.cadi.Lur;
import com.att.cadi.Permission;
import com.att.cadi.PropAccess;
import com.att.cadi.Symm;
import com.att.cadi.config.UsersDump;
import com.att.cadi.lur.LocalLur;
import com.att.cadi.lur.LocalPermission;

public class JU_LocalLur {

	@Test
	public void test() throws IOException {
		Symm symmetric = Symm.baseCrypt().obtain();
		LocalLur up;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(Symm.ENC.getBytes());
		symmetric.enpass("<pass>", baos);
		PropAccess ta = new PropAccess();
		Lur ml = up = new LocalLur(ta,"myname:groupA,groupB","admin:myname,yourname;suser:hisname,hername,m1234%"+baos.toString());
		
		Permission admin = new LocalPermission("admin");
		Permission suser = new LocalPermission("suser");
		
		// Check User fish
		assertTrue(ml.fish(new JUPrincipal("myname"),admin));
		assertTrue(ml.fish(new JUPrincipal("hisname"),admin));
		assertFalse(ml.fish(new JUPrincipal("noname"),admin));
		assertTrue(ml.fish(new JUPrincipal("itsname"),suser));
		assertTrue(ml.fish(new JUPrincipal("hername"),suser));
		assertFalse(ml.fish(new JUPrincipal("myname"),suser));
		
		
		// Check validate password
		assertTrue(up.validate("m1234",Type.PASSWORD, "<pass>".getBytes()));
		assertFalse(up.validate("m1234",Type.PASSWORD, "badPass".getBytes()));
		
		// Check fishAll
		Set<String> set = new TreeSet<String>();
		List<Permission> perms = new ArrayList<Permission>();
		ml.fishAll(new JUPrincipal("myname"), perms);
		for(Permission p : perms) {
			set.add(p.getKey());
		}
		assertEquals("[admin, groupA, groupB]",set.toString());
		UsersDump.write(System.out, up);
		System.out.flush();
		
	}
	
	// Simplistic Principal for testing purposes
	private static class JUPrincipal implements Principal {
		private String name;
		public JUPrincipal(String name) {
			this.name = name;
		}
//		@Override
		public String getName() {
			return name;
		}
	}

}
