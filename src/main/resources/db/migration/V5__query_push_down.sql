-- Per query: evaluate $filter/$orderby/$top/$skip in the database instead of in memory (see ARCHITECTURE.md §4).
ALTER TABLE query_definition ADD COLUMN push_down BOOLEAN NOT NULL DEFAULT FALSE;
