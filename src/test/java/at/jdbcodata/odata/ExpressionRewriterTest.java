package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExpressionRewriterTest {

    private static final String M = ExpressionRewriter.PATTERN_MARKER;

    @Test
    void removesBlanksAfterTheLambdaColonButNotInStrings() {
        assertThat(ExpressionRewriter.rewrite("Umsaetze/any(u: u/Betrag gt 1)")).isEqualTo("Umsaetze/any(u:u/Betrag gt 1)");
        assertThat(ExpressionRewriter.rewrite("A/all(x :  x/B eq 'a: b')")).isEqualTo("A/all(x:x/B eq 'a: b')");
        assertThat(ExpressionRewriter.rewrite("Name eq 'any(u: u)'")).isEqualTo("Name eq 'any(u: u)'");
        assertThat(ExpressionRewriter.rewrite("Zeit eq 10:00:00")).isEqualTo("Zeit eq 10:00:00");
    }

    @Test
    void matchesPatternBecomesMarkedContains() {
        assertThat(ExpressionRewriter.rewrite("matchesPattern(Name,'^L.*AG$')"))
                .isEqualTo("contains(Name,concat('" + M + "','^L.*AG$'))");
        assertThat(ExpressionRewriter.rewrite("not matchesPattern(tolower(Name), 'a,b') and X eq 'matchesPattern(1,2)'"))
                .isEqualTo("not contains(tolower(Name),concat('" + M + "','a,b')) and X eq 'matchesPattern(1,2)'");
    }

    @Test
    void countWithFilterBecomesCountingLambda() {
        assertThat(ExpressionRewriter.rewrite("Umsaetze/$count($filter=Betrag gt 100 and Status ne 'X') gt 2"))
                .isEqualTo("cast(Umsaetze/any(jdbcodata_c1:jdbcodata_c1/Betrag gt 100 and jdbcodata_c1/Status ne 'X'),Edm.Int64) gt 2");
        assertThat(ExpressionRewriter.rewrite("Umsaetze/$count($filter=year(Datum) eq 2025 and Produkt/Preis ge 1.5e3) desc"))
                .isEqualTo("cast(Umsaetze/any(jdbcodata_c1:year(jdbcodata_c1/Datum) eq 2025 and jdbcodata_c1/Produkt/Preis ge 1.5e3),Edm.Int64) desc");
        assertThat(ExpressionRewriter.rewrite("Umsaetze/$count($filter=Positionen/any(p:p/Menge gt 1) and Datum lt 2025-01-01)"))
                .isEqualTo("cast(Umsaetze/any(jdbcodata_c1:jdbcodata_c1/Positionen/any(p:p/Menge gt 1) and jdbcodata_c1/Datum lt 2025-01-01),Edm.Int64)");
        assertThat(ExpressionRewriter.rewrite("Umsaetze/$count gt 1")).isEqualTo("Umsaetze/$count gt 1");
        assertThat(ExpressionRewriter.rewrite("Umsaetze/$count($search=x) gt 1")).isEqualTo("Umsaetze/$count($search=x) gt 1");
    }

    @Test
    void expandKeepsItsCountPathAndRewritesNestedFilters() {
        assertThat(ExpressionRewriter.rewriteExpand("Sales/$count($filter=amount gt 1)")).isEqualTo("Sales/$count($filter=amount gt 1)");
        assertThat(ExpressionRewriter.rewriteExpand("Kunden($filter=Umsaetze/$count($filter=Betrag gt 1) gt 2;$select=Name),Produkt"))
                .isEqualTo("Kunden($filter=cast(Umsaetze/any(jdbcodata_c1:jdbcodata_c1/Betrag gt 1),Edm.Int64) gt 2;$select=Name),Produkt");
        assertThat(ExpressionRewriter.rewriteExpand("Umsaetze($filter=Pos/any(u: u/x))")).isEqualTo("Umsaetze($filter=Pos/any(u:u/x))");
    }

    @Test
    void catastrophicPatternsAreStopped() throws Exception {
        assertThat(EntityExpressionVisitor.matchesPattern("Linzer Bau AG", "^L.*AG$")).isTrue();
        long start = System.nanoTime();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> EntityExpressionVisitor.matchesPattern("a".repeat(40) + "!", "(.*a){12}$"))
                .isInstanceOf(org.apache.olingo.server.api.ODataApplicationException.class).hasMessageContaining("too expensive");
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    @Test
    void queryOptionsAreDecodedRewrittenAndEncoded() {
        assertThat(QueryRewriter.rewrite("$filter=A/any(u:%20u/B%20gt%201)&$top=2"))
                .isEqualTo("$filter=A/any(u:u/B%20gt%201)&$top=2");
        assertThat(QueryRewriter.rewrite("$filter=Name%20eq%20'a%26b'")).isEqualTo("$filter=Name%20eq%20'a%26b'");
        assertThat(QueryRewriter.rewrite("$search=u:%20x")).isEqualTo("$search=u:%20x");
        // parentheses: the search becomes a filter condition parsed by SearchSyntax
        assertThat(QueryRewriter.rewrite("$search=(a%20OR%20b)&$filter=X%20gt%201"))
                .isEqualTo("$filter=(X%20gt%201)%20and%20cast(@jdbcodata_search1,Edm.Boolean)&jdbcodata-search1=(a%20OR%20b)");
    }
}
