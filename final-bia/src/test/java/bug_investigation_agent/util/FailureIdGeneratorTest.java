package bug_investigation_agent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureIdGeneratorTest {

    @Test
    void dynamicNumericTokensProduceSameStableId() {
        String scenario1 = "verify product flow";
        String feature1 = "Product target";
        String step = "Then target card is visible";

        String errorA = "org.openqa.selenium.TimeoutException: waiting for visibility of "
                + "By.xpath: //android.view.ViewGroup[@resource-id='opportunityCard_click_147653']";
        String errorB = "org.openqa.selenium.TimeoutException: waiting for visibility of "
                + "By.xpath: //android.view.ViewGroup[@resource-id='opportunityCard_click_104712']";

        String idA = FailureIdGenerator.generate(scenario1, feature1, step, errorA);
        String idB = FailureIdGenerator.generate("another scenario label", "another feature label", step, errorB);

        assertThat(idA).isEqualTo(idB);
    }

    @Test
    void legacyAndStableAlgorithmsDifferForDynamicFailures() {
        String scenario = "verify product flow";
        String feature = "Product target";
        String step = "Then target card is visible";
        String error = "org.openqa.selenium.TimeoutException: By.xpath: "
                + "//android.view.ViewGroup[@resource-id='opportunityCard_click_147653']";

        String stableId = FailureIdGenerator.generate(scenario, feature, step, error);
        String legacyId = FailureIdGenerator.generateLegacy(scenario, feature, step, error);

        assertThat(stableId).isNotEqualTo(legacyId);
    }
}
