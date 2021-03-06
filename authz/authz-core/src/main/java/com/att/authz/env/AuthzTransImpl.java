/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.authz.env;

import java.security.Principal;

import javax.servlet.http.HttpServletRequest;

import com.att.authz.org.Organization;
import com.att.authz.org.OrganizationFactory;
import com.att.cadi.Lur;
import com.att.cadi.Permission;
import com.att.inno.env.LogTarget;
import com.att.inno.env.impl.BasicTrans;

public class AuthzTransImpl extends BasicTrans implements AuthzTrans {
	private static final String TRUE = "true";
	private Principal user;
	private String ip,agent,meth,path;
	private int port;
	private Lur lur;
	private Organization org;
	private String force;
	private boolean futureRequested;

	public AuthzTransImpl(AuthzEnv env) {
		super(env);
		ip="n/a";
		org=null;
	}

	/**
	 * @see com.att.authz.env.AuthTrans#set(javax.servlet.http.HttpServletRequest)
	 */
	@Override
	public AuthzTrans set(HttpServletRequest req) {
		user = req.getUserPrincipal();
		ip = req.getRemoteAddr();
		port = req.getRemotePort();
		agent = req.getHeader("User-Agent");
		meth = req.getMethod();
		path = req.getPathInfo();
		force = req.getParameter("force");
		futureRequested = TRUE.equalsIgnoreCase(req.getParameter("request"));
		org=null;
		return this;
	}
	
	@Override
	public void setUser(Principal p) {
		user = p;
	}

	/**
	 * @see com.att.authz.env.AuthTrans#user()
	 */
	@Override
	public String user() {
		return user==null?"n/a":user.getName();
	}
	
	/**
	 * @see com.att.authz.env.AuthTrans#getUserPrincipal()
	 */
	@Override
	public Principal getUserPrincipal() {
		return user;
	}

	/**
	 * @see com.att.authz.env.AuthTrans#ip()
	 */
	@Override
	public String ip() {
		return ip;
	}

	/**
	 * @see com.att.authz.env.AuthTrans#port()
	 */
	@Override
	public int port() {
		return port;
	}


	/* (non-Javadoc)
	 * @see com.att.authz.env.AuthzTrans#meth()
	 */
	@Override
	public String meth() {
		return meth;
	}

	/* (non-Javadoc)
	 * @see com.att.authz.env.AuthzTrans#path()
	 */
	@Override
	public String path() {
		return path;
	}

	/**
	 * @see com.att.authz.env.AuthTrans#agent()
	 */
	@Override
	public String agent() {
		return agent;
	}

	@Override
	public AuthzEnv env() {
		return (AuthzEnv)delegate;
	}
	
	@Override
	public boolean forceRequested() {
		return TRUE.equalsIgnoreCase(force);
	}
	
	public void forceRequested(boolean force) {
		this.force = force?TRUE:"false";
	}
	
	@Override
	public boolean moveRequested() {
		return "move".equalsIgnoreCase(force);
	}

	@Override
	public boolean futureRequested() {
		return futureRequested;
	}
	

	@Override
	public void setLur(Lur lur) {
		this.lur = lur;
	}
	
	@Override
	public boolean fish(Permission p) {
		if(lur!=null) {
			return lur.fish(user, p);
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see com.att.authz.env.AuthzTrans#org()
	 */
	@Override
	public Organization org() {
		if(org==null) {
			try {
				if((org = OrganizationFactory.obtain(env(), user()))==null) {
					org = Organization.NULL;
				}
			} catch (Exception e) {
				org = Organization.NULL;
			}
		} 
		return org;
	}

	/* (non-Javadoc)
	 * @see com.att.authz.env.AuthzTrans#logAuditTrailOnly(com.att.inno.env.LogTarget)
	 */
	@Override
	public void logAuditTrail(LogTarget lt) {
		if(lt.isLoggable()) {
			StringBuilder sb = new StringBuilder();
			auditTrail(1, sb);
			lt.log(sb);
		}
	}
}
