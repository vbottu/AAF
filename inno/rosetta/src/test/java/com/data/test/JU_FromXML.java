/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.data.test;

import java.io.Reader;
import java.io.StringReader;

import org.junit.Test;

import com.att.inno.env.Env;
import com.att.inno.env.LogTarget;
import com.att.inno.env.TimeTaken;
import com.att.inno.env.Trans;
import com.att.inno.env.Trans.Metric;
import com.att.inno.env.impl.EnvFactory;
import com.att.inno.env.jaxb.JAXBmar;
import com.att.inno.env.jaxb.JAXBumar;
import com.att.inno.env.util.StringBuilderWriter;
import com.att.rosetta.InXML;
import com.att.rosetta.Out;
import com.att.rosetta.OutJson;
import com.att.rosetta.OutRaw;
import com.att.rosetta.OutXML;

import s.xsd.LargerData;

public class JU_FromXML {
	private static int ITERATIONS = 1;
		;
	
	private final static String xml = 
	"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
	"<LargerData xmlns=\"urn:s:xsd\">\n" +
	"   <SampleData>\n" +
	"      <id>sd object 1</id>\n" +
	"        <date>1346765355134</date>\n" +
	"        <item>Item 1.1</item>\n" +
	"        <item>Item 1.2</item>\n" +
	"   </SampleData>\n" +
	"   <SampleData>\n" +
	"        <id>sd object 2</id>\n" +
	"        <date>1346765355134</date>\n" +
	"        <item>Item 2.1</item>\n" +
	"        <item>Item 2.2</item>\n" +
	"   </SampleData>\n" +
	"   <fluff>MyFluff</fluff>\n" +
	"</LargerData>\n";
	
	
	@Test
	public void test() throws Exception {
		InXML inXML = new InXML(LargerData.class);
		
		System.out.println(xml);
		StringBuilderWriter sbw = new StringBuilderWriter(1024);
		
		Reader rdr = new StringReader(xml);
		
		new OutRaw().extract(rdr, sbw, inXML);
		System.out.println(sbw.getBuffer());
	}
	

	@Test
	public void xml2JSON() throws Exception {
		System.out.println("*** XML -> JSON  (No Warm up) ***");
		Out jout = new OutJson();
		InXML inXML = new InXML(LargerData.class);

		StringBuilderWriter sbw = new StringBuilderWriter(1024);
		
		Trans trans;
		Report report = new Report(ITERATIONS,"XML");
		do {
			sbw.reset();
			trans = EnvFactory.newTrans();
			Reader sr = new StringReader(xml);
			TimeTaken tt = trans.start("Parse XML", Env.XML);
			try {
				jout.extract(sr, sbw, inXML);
			} finally {
				tt.done();
			}
			report.glean(trans,Env.XML);
		} while(report.go());
		
		report.report(sbw);
		System.out.println(sbw.toString());
	}

	@Test
	public void xml2XML() throws Exception {
		System.out.println("*** XML -> (Event Queue) -> XML (No Warm up) ***");
		Out xout = new OutXML("LargerData");
		InXML inXML = new InXML(LargerData.class);

		StringBuilderWriter sbw = new StringBuilderWriter(1024);
		
		Trans trans;
		Report report = new Report(ITERATIONS,"XML");
		do {
			sbw.reset();
			trans = EnvFactory.newTrans();
			Reader sr = new StringReader(xml);
			TimeTaken tt = trans.start("Parse XML", Env.XML);
			try {
				xout.extract(sr, sbw, inXML);
			} finally {
				tt.done();
			}
			report.glean(trans,Env.XML);
		} while(report.go());
		
		report.report(sbw);
		System.out.println(sbw.toString());
	}
	
	
	@Test
	public void warmup() throws Exception {
		if(ITERATIONS>20) {
			System.out.println("*** Warmup JAXB ***");
			
			JAXBumar jaxbUmar = new JAXBumar(LargerData.class);
			JAXBmar jaxBmar = new JAXBmar(LargerData.class);
			//jaxBmar.asFragment(true);
			//jaxBmar.pretty(true);
			StringBuilderWriter sbw = new StringBuilderWriter(1024);
	

			LargerData ld;
			Trans trans;
			Report report = new Report(ITERATIONS,"XML");
			do {
				sbw.reset();
				trans = EnvFactory.newTrans();
				TimeTaken all = trans.start("Combo", Env.SUB);
				try {
					TimeTaken tt = trans.start("JAXB Unmarshal", Env.XML);
					try {
						ld = jaxbUmar.unmarshal(LogTarget.NULL, xml);
					} finally {
						tt.done();
					}
					tt = trans.start("JAXB marshal", Env.XML);
					try {
						jaxBmar.marshal(LogTarget.NULL, ld, sbw);
					} finally {
						tt.done();
					}
				} finally {
					all.done();
				}
				report.glean(trans,Env.XML);
			} while(report.go());
			
			report.report(sbw);
			System.out.println(sbw.toString());
		}
	}
	@Test
	public void xml2jaxb2xml() throws Exception {
		System.out.println("*** XML -> JAXB Object -> XML ***");
		JAXBumar jaxbUmar = new JAXBumar(LargerData.class);
		JAXBmar jaxBmar = new JAXBmar(LargerData.class);
		//jaxBmar.asFragment(true);
		//jaxBmar.pretty(true);
		StringBuilderWriter sbw = new StringBuilderWriter(1024);

		LargerData ld;
		Trans trans;
		Report report = new Report(ITERATIONS,"XML");
		do {
			sbw.reset();
			trans = EnvFactory.newTrans();
			TimeTaken all = trans.start("Combo", Env.SUB);
			try {
				TimeTaken tt = trans.start("JAXB Unmarshal", Env.XML);
				try {
					ld = jaxbUmar.unmarshal(LogTarget.NULL, xml);
				} finally {
					tt.done();
				}
				tt = trans.start("JAXB marshal", Env.XML);
				try {
					jaxBmar.marshal(LogTarget.NULL, ld, sbw);
				} finally {
					tt.done();
				}
			} finally {
				all.done();
			}
			report.glean(trans,Env.XML);
		} while(report.go());
		
		report.report(sbw);
		System.out.println(sbw.toString());	}

	@Test
	public void xml2jaxb2PrettyXml() throws Exception {
		System.out.println("*** XML -> JAXB Object -> Pretty XML ***");
		JAXBumar jaxbUmar = new JAXBumar(LargerData.class);
		JAXBmar jaxBmar = new JAXBmar(LargerData.class);
		//jaxBmar.asFragment(true);
		jaxBmar.pretty(true);
		StringBuilderWriter sbw = new StringBuilderWriter(1024);

		Trans trans = EnvFactory.newTrans();
		LargerData ld;
		for(int i=0;i<ITERATIONS;++i) {
			sbw.reset();
			TimeTaken all = trans.start("Combo", Env.SUB);
			try {
				TimeTaken tt = trans.start("JAXB Unmarshal", Env.XML);
				try {
					ld = jaxbUmar.unmarshal(LogTarget.NULL, xml);
				} finally {
					tt.done();
				}
				tt = trans.start("JAXB marshal", Env.XML);
				try {
					jaxBmar.marshal(LogTarget.NULL, ld, sbw);
				} finally {
					tt.done();
				}
			} finally {
				all.done();
			}
		}
		sbw.append('\n');
		Metric m;
		if(ITERATIONS>20) {
			m = trans.auditTrail(0,null);
		} else {
			m = trans.auditTrail(0, sbw.getBuffer());
			System.out.println(sbw.getBuffer());
		}
		System.out.println(ITERATIONS + " entries, Total Time: " + m.total + "ms, Avg Time: " + m.total/ITERATIONS + "ms");
	}

}
