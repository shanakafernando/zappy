<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2//EN">
<!-- Created by Cosmin Stefan-Dobrin for testing of Zaproxy Spider, during GSOC 2012 -->
<%@page import="com.sectooladdict.spider.Utils"%>
<%@page import="com.mysql.jdbc.Util"%>
<%@include file="/spider/must-visit-page.jsp"%>
<head>
<title>Spider Basic Test</title>
</head>
<body>

	<h2>Test 5</h2>

	<p>Pages in this section should by used for testing crawlers and
		spiders. The spider should explore the links that are provided below.</p>

	<p>This test is for links with parameters.</p>
	<br />
	<%
		String urlBase = Utils.getBaseUrl(request);
	%>
	<a href="<%=urlBase + "a.jsp?page=2"%>">Visit page</a>


</body>
