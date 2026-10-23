package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.adpilot.analytics.EconomicGoalModels.Goal;
import br.com.adpilot.analytics.EconomicGoalModels.Input;
import br.com.adpilot.analytics.EconomicGoalModels.LinkRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EconomicGoalServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-22T21:15:00Z");
    private final EconomicGoalRepository repository=mock(EconomicGoalRepository.class);
    private final EconomicGoalService service=new EconomicGoalService(
            repository,Clock.fixed(NOW,ZoneOffset.UTC));

    private static Input good() {
        return new Input("Vestido midi azul",19_900L,7_800L,
                400,500,200L,300L,200L,1_000L,1_700);
    }

    @Test void preservesExactCalculatorTargetsAndAllEditableCosts() {
        when(repository.create(any())).thenAnswer(invocation->invocation.getArgument(0));
        Goal plan=service.save(good());
        assertEquals(19_900,plan.priceCents());
        assertEquals(7_800,plan.productCostCents());
        assertEquals(400,plan.taxBasisPoints());
        assertEquals(500,plan.paymentBasisPoints());
        assertEquals(1_700,plan.desiredProfitBasisPoints());
        assertEquals(5_226,plan.targetCpaCents());
        assertEquals(381,plan.targetRoasHundredths());
        assertEquals("Vestido midi azul",plan.productLabel());
        assertEquals(NOW,plan.createdAt());
        verify(repository).create(plan);
    }

    @Test void refusesAnyMissingCostRatherThanSilentlyTreatingItAsZero() {
        var a=good();
        for(var input:new Input[]{
                new Input(a.productLabel(),a.priceCents(),null,a.taxBasisPoints(),
                        a.paymentBasisPoints(),a.paymentFixedCents(),a.shippingSubsidyCents(),
                        a.packagingCents(),a.otherCostsCents(),a.desiredProfitBasisPoints()),
                new Input(a.productLabel(),a.priceCents(),a.productCostCents(),null,
                        a.paymentBasisPoints(),a.paymentFixedCents(),a.shippingSubsidyCents(),
                        a.packagingCents(),a.otherCostsCents(),a.desiredProfitBasisPoints()),
                new Input(a.productLabel(),a.priceCents(),a.productCostCents(),a.taxBasisPoints(),
                        a.paymentBasisPoints(),null,a.shippingSubsidyCents(),
                        a.packagingCents(),a.otherCostsCents(),a.desiredProfitBasisPoints()),
                new Input(a.productLabel(),a.priceCents(),a.productCostCents(),a.taxBasisPoints(),
                        a.paymentBasisPoints(),a.paymentFixedCents(),a.shippingSubsidyCents(),
                        a.packagingCents(),a.otherCostsCents(),null)
        }) {
            assertEquals(400,assertThrows(ResponseStatusException.class,
                    ()->service.save(input)).getStatusCode().value());
        }
        verifyNoInteractions(repository);
    }

    @Test void rejectsUnprofitablePlansAndInvalidPercentages() {
        var a=good();
        var unsustainable=new Input("Produto inviável",10_000L,9_000L,400,500,
                200L,300L,200L,1_000L,1_700);
        var negative=new Input("Produto",10_000L,-1L,400,500,
                200L,300L,200L,1_000L,1_700);
        var invalidPercent=new Input("Produto",10_000L,2_000L,10_001,500,
                200L,300L,200L,1_000L,1_700);
        for(var input:new Input[]{unsustainable,negative,invalidPercent}) {
            assertEquals(400,assertThrows(ResponseStatusException.class,
                    ()->service.save(input)).getStatusCode().value());
        }
        verifyNoInteractions(repository);
    }

    @Test void rejectsControlCharactersAndUnknownGoal() {
        var a=good();
        var name=new Input("Item\nInjected",a.priceCents(),a.productCostCents(),
                a.taxBasisPoints(),a.paymentBasisPoints(),a.paymentFixedCents(),
                a.shippingSubsidyCents(),a.packagingCents(),a.otherCostsCents(),
                a.desiredProfitBasisPoints());
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.save(name)).getStatusCode().value());
        var id=UUID.randomUUID();
        assertEquals(404,assertThrows(ResponseStatusException.class,
                ()->service.associate("123","456",new LinkRequest(id,true)))
                .getStatusCode().value());
    }

    @Test void explicitConsentAndKnownBrlCampaignAreMandatory() {
        UUID id=UUID.randomUUID();
        when(repository.goalExists(id)).thenReturn(true);
        when(repository.eligibleCampaign("123","456")).thenReturn(true);

        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.associate("123","456",new LinkRequest(id,false)))
                .getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.associate("1x3","456",new LinkRequest(id,true)))
                .getStatusCode().value());
        verify(repository,never()).link(any(),any(),any());

        var result=service.associate("123","456",new LinkRequest(id,true));
        assertEquals("LINKED",result.status());
        assertEquals(id,result.goalId());
        verify(repository).eligibleCampaign("123","456");
        verify(repository).link("123","456",id);
    }

    @Test void foreignOrUnknownCampaignCannotBeAssociated() {
        UUID id=UUID.randomUUID();
        when(repository.goalExists(id)).thenReturn(true);
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.associate("123","999",new LinkRequest(id,true)))
                .getStatusCode().value());
        verify(repository,never()).link(any(),any(),any());
    }

    @Test void unlinkOnlyLocalMapping() {
        when(repository.unlink("123","456")).thenReturn(1);
        assertEquals("UNLINKED",service.unlink("123","456").status());
        assertEquals(404,assertThrows(ResponseStatusException.class,
                ()->service.unlink("123","999")).getStatusCode().value());
        verify(repository).unlink("123","456");
    }
}
