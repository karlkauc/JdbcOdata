# Contributing

Thanks for helping. For anything larger than a small fix, please open an issue first so we can agree
on the approach.

## Build and test

Java 21 is the only requirement; Maven comes with the wrapper.

```bash
./mvnw verify                 # build, tests, coverage gate (85 % line coverage)
./mvnw verify -Psecurity      # additionally checks dependencies for known CVEs
```

The PostgreSQL integration tests need Docker and are skipped without it. Browser tests are in `e2e/`, see
`e2e/README.md`. Run `e2e/check.mjs` after changes to the admin UI.

## Pull requests

- Keep them focused, with tests for new behaviour and for bug fixes.
- Match the style of the surrounding code. Code, UI and commit messages are in English.
- Security-relevant behaviour (`JdbcUrlPolicy`, `SqlValidator`, identity resolution) needs a test that fails
  without the change.
- Do not add dependencies under a licence that is incompatible with MIT.

Contributions are licensed under the MIT licence of this project.

## Reporting bugs

Use the issue templates. For security problems see [SECURITY.md](SECURITY.md).
