package com.xb.sgc.papererase.safety;

import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RiskGateTest {
    @Test
    public void requiresLocalVerifyWhenAnyRiskConditionFails() {
        RegionValidator.ValidationResult validation = validated(0.99);

        assertTrue(RiskGate.requiresLocalVerify(RiskGate.PageContext.stable(null), validation));
        assertTrue(RiskGate.requiresLocalVerify(RiskGate.PageContext.stable(" "), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withPatternGroupId(null), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withPatternGroupId(" "), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withConsensusState(null), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withConsensusState("Stable"), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withConsensusState("unknown"), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withStablePattern(false), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withEdgeMatchesPattern(false), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withJavaBlankGap(false), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withMaskTouchesBoundary(true), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withReadingRotation(90), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withDoublePage(true), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withHeterogeneousFirstOrLast(true), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withConsensusState("mixed").withPatternGroupId(null), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withConsensusState("uncertain"), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withBodyBoundaryConflict(true), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withPageSequenceIncomplete(true), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withMissingPageRisk(true), validation));
        assertTrue(RiskGate.requiresLocalVerify(stablePage(), validated(0.969)));
        assertTrue(RiskGate.requiresLocalVerify(stablePage(), validated(Double.NaN)));
        assertTrue(RiskGate.requiresLocalVerify(stablePage(), RegionValidator.ValidationResult.rejectedResult("bad region")));
        assertTrue(RiskGate.requiresLocalVerify(stablePage(), validatedForPage("different-page", 0.99)));
    }

    @Test
    public void skipsLocalVerifyOnlyForFullyStableValidatedPage() {
        assertFalse(RiskGate.requiresLocalVerify(stablePage(), validated(0.97)));
        assertFalse(RiskGate.requiresLocalVerify(stablePage(), validated(0.99)));
        assertFalse(RiskGate.requiresLocalVerify(stablePage().withConsensusState("mixed"), validated(0.99)));
        assertTrue(RiskGate.requiresLocalVerify(stablePage().withConsensusState("mixed").withStablePattern(false), validated(0.99)));
    }

    private RiskGate.PageContext stablePage() {
        return RiskGate.PageContext.stable("page-1")
                .withPatternGroupId("g-top")
                .withConsensusState("stable")
                .withReadingRotation(0);
    }

    private RegionValidator.ValidationResult validated(double confidence) {
        return validatedForPage("page-1", confidence);
    }

    private RegionValidator.ValidationResult validatedForPage(String pageId, double confidence) {
        BufferedImage image = blankPage();
        for (int y = 9; y <= 14; y++) {
            for (int x = 11; x <= 18; x++) {
                image.setRGB(x, y, Color.BLACK.getRGB());
            }
        }
        EraseRegion region = new EraseRegion();
        region.region_id = "r1";
        region.x1 = 0.10;
        region.y1 = 0.04;
        region.x2 = 0.20;
        region.y2 = 0.08;
        region.confidence = confidence;

        BodyBoundary boundary = new BodyBoundary();
        boundary.y = 0.16;
        boundary.basis = "java";

        return RegionValidator.validate(
                new RegionValidator.PageLocateResult(pageId, "safe_to_erase", Arrays.asList(region), boundary),
                image);
    }

    private BufferedImage blankPage() {
        BufferedImage image = new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        return image;
    }
}
