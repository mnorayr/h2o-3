package water.api;


import water.Iced;

public class ImportSQLTableV99 extends RequestSchema<Iced,ImportSQLTableV99> {
  
  //Input fields
  @API(help="connection_url", required = true)
  String connection_url;
  
  @API(help="table", required = true)
  String table;

  @API(help="username", required = true)
  String username;

  @API(help="password", required = true)
  String password;
  
  @API(help="columns")
  String columns = "*";

  @API(help="optimize")
  boolean optimize = true;
  
}
