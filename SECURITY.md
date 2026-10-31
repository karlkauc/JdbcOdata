# Security policy

## Reporting a vulnerability

Please do not open a public issue. Use
[GitHub private vulnerability reporting](https://github.com/karlkauc/JdbcOdata/security/advisories/new).

Include the affected version, the configuration involved and steps to reproduce. This is a spare-time
project: expect a first answer within a week.

## Supported versions

Security fixes go into the latest release only.

## Scope

JdbcOdata connects to databases on behalf of its callers, so reports about authentication bypass,
access to another caller's data, SQL injection and credential exposure matter most. An admin who can
configure datasources is trusted, but should not be able to read files on the server or run code in it;
reports crossing that boundary are in scope as well. Running the `demo` profile on a public interface is
not supported.

The threat model and known limits are described in the
[security documentation](https://karlkauc.github.io/JdbcOdata/security/).
