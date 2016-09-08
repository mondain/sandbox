<%@ page import="org.springframework.context.ApplicationContext,org.springframework.web.context.WebApplicationContext,com.red5pro.support.Application"%>
<!DOCTYPE HTML>
<html>
<head>
<title>Stream Switcher</title>
<%
    ApplicationContext appCtx = (ApplicationContext) getServletContext().getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
    Application app = (Application) appCtx.getBean("web.handler");
    String status = null;
    String scopePath = request.getParameter("scopePath") == null ? "redsupport339" : request.getParameter("scopePath");
    String streamName = request.getParameter("streamName");
    if (scopePath != null && streamName != null) {
        if (app.switchLiveStream(scopePath, streamName)) {
            status = "Stream switched";
        } else {
            status = "Stream switch failed";
        }
    }
    String activeStreamName = app.getActiveFeedName(scopePath);
%>
</head>
<body>
<!-- 
Scope path: <%= scopePath %>
Stream name: <%= streamName %>
Request: <%= request.getParameterMap() %>
-->
<% if (status != null) { %>
Switch status: <%= status %>
<br />
<% } %>
Currently active feed: <%= activeStreamName %>
<br />
<br />
<form method="post" action="switcher.jsp">
Scope path: <input type="text" name="scopePath" value="<%= scopePath %>"/>
<br />
Stream name: <input type="text" name="streamName"/>
<br />
<input type="submit" value="Switch!"/>
</form>
</body>
</html> 