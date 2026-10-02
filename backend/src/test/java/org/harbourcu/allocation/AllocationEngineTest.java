package org.harbourcu.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class AllocationEngineTest {
    @Test
    void reproducesSheetATotalsWithoutRoundingDrift() {
        long[] cedis = {5000, 7000, 12000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000};
        List<AllocationEngine.Participant> participants = new ArrayList<>();
        for (int i = 0; i < cedis.length; i++) {
            participants.add(new AllocationEngine.Participant(i + 1, cedis[i] * 100));
        }
        var shares = AllocationEngine.allocate(participants, 4_227_126, 4_543_500);
        assertThat(shares).hasSize(12);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::costPesewas).sum()).isEqualTo(4_227_126);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::maturityValuePesewas).sum()).isEqualTo(4_543_500);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::incomePesewas).sum()).isEqualTo(316_374);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::closingBalancePesewas).sum()).isEqualTo(4_516_374);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::ratioBasisPoints).sum()).isEqualTo(10_000);
        assertThat(shares.get(0).costPesewas()).isEqualTo(503_229);
    }

    @Test
    void tiesGoToLowestMemberNumberIndependentlyOfInputOrder() {
        var shares = AllocationEngine.allocate(List.of(
            new AllocationEngine.Participant(2, 100),
            new AllocationEngine.Participant(1, 100)), 1, 1);
        assertThat(shares.get(1).costPesewas()).isEqualTo(1);
        assertThat(shares.get(0).costPesewas()).isZero();
    }

    @Test
    void moneyBoundaryRejectsFractionalPesewas() {
        assertThatThrownBy(() -> AllocationEngine.toPesewas(new BigDecimal("1.001")))
            .isInstanceOf(ArithmeticException.class);
        assertThat(AllocationEngine.toCedis(12345)).isEqualByComparingTo("123.45");
    }

    @Property(tries = 250)
    void totalsAlwaysReconcile(@ForAll("positiveTotals") long total) {
        var participants = List.of(new AllocationEngine.Participant(3, 3),
            new AllocationEngine.Participant(1, 5), new AllocationEngine.Participant(2, 7));
        var shares = AllocationEngine.allocate(participants, total, total + 41);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::costPesewas).sum()).isEqualTo(total);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::maturityValuePesewas).sum()).isEqualTo(total + 41);
        assertThat(shares.stream().mapToLong(AllocationEngine.Share::ratioBasisPoints).sum()).isEqualTo(10_000);
        assertThat(shares).allSatisfy(share -> {
            assertThat(share.costPesewas()).isGreaterThanOrEqualTo(0L);
            assertThat(share.maturityValuePesewas()).isGreaterThanOrEqualTo(0L);
        });
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<Long> positiveTotals() {
        return net.jqwik.api.Arbitraries.longs().between(0, 1_000_000_000L);
    }
}
