package at.jdbcodata.query;

/** Whether a query passes the caller to the database: as its datasource says, or always, or never. */
public enum PassCaller { INHERIT, ON, OFF }
