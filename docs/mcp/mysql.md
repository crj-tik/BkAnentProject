{
"mcp": {
"servers": {
"bk_mysql_readonly": {
"type": "http",
"url": "http://127.0.0.1:18081/mcp",
"headers": {
"Authorization": "Bearer <MYSQL_MCP_TOKEN 的值>"
},
"timeoutMs": 60000
}
}
}
}