package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ResponseBudgetTest {

    @Test
    void largeResultsShareTheBudget() {
        ResponseBudget budget = new ResponseBudget(10_000, 0);
        ResponseBudget.Reservation first = budget.reserve(6_000);
        assertThat(budget.reservedRows()).isEqualTo(6_000);
        assertThatThrownBy(() -> budget.reserve(6_000)).isInstanceOf(TooManyExecutionsException.class)
                .hasMessageContaining("busy");
        first.close();
        assertThat(budget.reservedRows()).isZero();
        budget.reserve(6_000).close();
    }

    @Test
    void smallResultsNeverWait() {
        ResponseBudget budget = new ResponseBudget(10_000, 0);
        ResponseBudget.Reservation all = budget.reserve(10_000);
        budget.reserve(ResponseBudget.FREE_ROWS - 1).close();
        assertThat(budget.reservedRows()).isEqualTo(10_000);
        all.close();
    }

    @Test
    void resultAboveTheBudgetRunsAlone() {
        ResponseBudget budget = new ResponseBudget(10_000, 0);
        ResponseBudget.Reservation huge = budget.reserve(50_000);
        assertThat(budget.reservedRows()).isEqualTo(10_000);
        assertThatThrownBy(() -> budget.reserve(2_000)).isInstanceOf(TooManyExecutionsException.class);
        huge.close();
        assertThat(budget.reservedRows()).isZero();
    }

    @Test
    void closingTwiceGivesBackOnce() {
        ResponseBudget budget = new ResponseBudget(10_000, 0);
        ResponseBudget.Reservation first = budget.reserve(4_000);
        ResponseBudget.Reservation second = budget.reserve(4_000);
        first.close();
        first.close();
        assertThat(budget.reservedRows()).isEqualTo(4_000);
        second.close();
    }

    @Test
    void waitsForRoom() throws Exception {
        ResponseBudget budget = new ResponseBudget(10_000, 10_000);
        ResponseBudget.Reservation full = budget.reserve(10_000);
        CompletableFuture<ResponseBudget.Reservation> waiting = CompletableFuture.supplyAsync(() -> budget.reserve(5_000));
        Thread.sleep(50);
        assertThat(waiting).isNotDone();
        full.close();
        waiting.get(5, TimeUnit.SECONDS).close();
        assertThat(budget.reservedRows()).isZero();
    }

    @Test
    void budgetFollowsTheHeap() {
        assertThat(ResponseBudget.forHeap(1L << 30)).isEqualTo(524_288);
        assertThat(ResponseBudget.forHeap(1L << 20)).isEqualTo(ResponseBudget.FREE_ROWS);
        assertThat(new ResponseBudget(0, 0).rows()).isPositive();
    }
}
