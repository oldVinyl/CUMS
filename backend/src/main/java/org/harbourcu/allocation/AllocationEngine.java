package org.harbourcu.allocation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure, deterministic allocation using whole pesewas and largest remainders. */
public final class AllocationEngine {
    public record Participant(long memberNumber, long participationPesewas) {}

    public record Share(long memberNumber, long participationPesewas, long ratioBasisPoints,
                        long costPesewas, long maturityValuePesewas, long incomePesewas,
                        long closingBalancePesewas) {
        public BigDecimal ratioPercent() {
            return BigDecimal.valueOf(ratioBasisPoints, 2);
        }
    }

    private AllocationEngine() {}

    public static long toPesewas(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Amount must be non-negative");
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    public static BigDecimal toCedis(long pesewas) {
        return BigDecimal.valueOf(pesewas, 2);
    }

    public static List<Share> allocate(List<Participant> participants, long costPesewas,
                                       long maturityValuePesewas) {
        if (participants == null || participants.isEmpty() || costPesewas < 0 || maturityValuePesewas < 0) {
            throw new IllegalArgumentException("Participants and non-negative totals are required");
        }
        Set<Long> seen = new HashSet<>();
        long pool = 0;
        for (Participant participant : participants) {
            if (participant.memberNumber() <= 0 || participant.participationPesewas() <= 0
                || !seen.add(participant.memberNumber())) {
                throw new IllegalArgumentException("Each member needs a unique positive participation");
            }
            pool = Math.addExact(pool, participant.participationPesewas());
        }
        Map<Long, Long> cost = distribute(participants, pool, costPesewas);
        Map<Long, Long> fv = distribute(participants, pool, maturityValuePesewas);
        Map<Long, Long> ratio = distribute(participants, pool, 10_000L);
        List<Share> result = new ArrayList<>();
        for (Participant participant : participants) {
            long member = participant.memberNumber();
            long income = Math.subtractExact(fv.get(member), cost.get(member));
            result.add(new Share(member, participant.participationPesewas(), ratio.get(member),
                cost.get(member), fv.get(member), income,
                Math.addExact(participant.participationPesewas(), income)));
        }
        return List.copyOf(result);
    }

    private record Remainder(long memberNumber, BigInteger fraction) {}

    private static Map<Long, Long> distribute(List<Participant> participants, long pool, long total) {
        BigInteger divisor = BigInteger.valueOf(pool);
        Map<Long, Long> shares = new HashMap<>();
        List<Remainder> fractions = new ArrayList<>();
        long assigned = 0;
        for (Participant participant : participants) {
            BigInteger numerator = BigInteger.valueOf(total)
                .multiply(BigInteger.valueOf(participant.participationPesewas()));
            BigInteger[] divRem = numerator.divideAndRemainder(divisor);
            long floor = divRem[0].longValueExact();
            shares.put(participant.memberNumber(), floor);
            assigned = Math.addExact(assigned, floor);
            fractions.add(new Remainder(participant.memberNumber(), divRem[1]));
        }
        fractions.sort(Comparator.comparing(Remainder::fraction).reversed()
            .thenComparingLong(Remainder::memberNumber));
        long remaining = total - assigned;
        for (int i = 0; i < remaining; i++) {
            Remainder remainder = fractions.get(i);
            shares.put(remainder.memberNumber(), shares.get(remainder.memberNumber()) + 1);
        }
        return shares;
    }
}

