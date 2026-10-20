package br.com.adpilot.commerce;

import java.util.List;

/**
 * Only aggregate counts and fixed classification labels. No order identifiers,
 * payment identifiers, customer data, raw gateway values or monetary amounts.
 */
public record PaymentAuditResponse(
        boolean complete,
        int pagesFetched,
        int recordsFetched,
        int uniqueOrders,
        Long upstreamTotalCount,
        int duplicateRecords,
        long paidOrders,
        long paidWithValidDate,
        long paidWithoutDate,
        long paidWithInvalidDate,
        List<Bucket> paymentStatuses,
        List<Bucket> undatedByCreationMonth,
        List<Bucket> undatedByGateway,
        List<Bucket> undatedByStorefront,
        List<Bucket> undatedByAppAssociation,
        List<Bucket> undatedByOrderStatus,
        List<Bucket> datedPaymentsByMonth,
        List<String> warnings) {

    public record Bucket(String label, long count) { }
}
