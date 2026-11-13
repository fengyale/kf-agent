import java.sql.*;
var c = DriverManager.getConnection("jdbc:sqlite:data/research.db");
var rs = c.createStatement().executeQuery("SELECT id, task_id, seq, type FROM event ORDER BY id");
while (rs.next()) System.out.println(rs.getInt("id") + " | " + rs.getString("task_id") + " | seq=" + rs.getInt("seq") + " | " + rs.getString("type"));
c.close();
System.exit(0);
