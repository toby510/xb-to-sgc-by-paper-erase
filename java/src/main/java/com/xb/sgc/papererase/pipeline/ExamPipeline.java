package com.xb.sgc.papererase.pipeline;

import com.xb.sgc.papererase.erase.InkMaskEraser;
import com.xb.sgc.papererase.image.OrientationNormalizer;
import com.xb.sgc.papererase.image.RoiTransform;
import com.xb.sgc.papererase.input.PageBatcher;
import com.xb.sgc.papererase.model.ExamModels.AuditResponse;
import com.xb.sgc.papererase.model.ExamModels.BodyBoundary;
import com.xb.sgc.papererase.model.ExamModels.EraseRegion;
import com.xb.sgc.papererase.model.ExamModels.ExamInput;
import com.xb.sgc.papererase.model.ExamModels.LocateResponse;
import com.xb.sgc.papererase.model.ExamModels.PageDirection;
import com.xb.sgc.papererase.model.ExamModels.PageInput;
import com.xb.sgc.papererase.model.ExamModels.PatternGroup;
import com.xb.sgc.papererase.model.ExamModels.PatternResponse;
import com.xb.sgc.papererase.model.ExamModels.VerifyResponse;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageOutcome;
import com.xb.sgc.papererase.pipeline.ExamOutcome.PageTransforms;
import com.xb.sgc.papererase.safety.RegionValidator;
import com.xb.sgc.papererase.safety.RiskGate;
import com.xb.sgc.papererase.vlm.ResponseParser;
import com.xb.sgc.papererase.vlm.VlmClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ExamPipeline {
    private static final double MIN_DIRECTION_CONFIDENCE = 0.90;
    private final VlmClient vlm;

    public ExamPipeline(VlmClient vlm) {
        if (vlm == null) {
            throw new IllegalArgumentException("vlm is required");
        }
        this.vlm = vlm;
    }

    public ExamOutcome process(ExamInput exam, RunContext context) {
        Map<String, BufferedImage> originals = readOriginals(exam);
        PatternBundle bundle;
        try {
            bundle = buildPattern(exam, originals);
        } catch (ResponseParser.ParseException e) {
            return wholeExamFallback(exam, originals, "pattern_protocol_error", e.getMessage());
        } catch (RuntimeException e) {
            return wholeExamFallback(exam, originals, "pattern_protocol_error", e.getMessage());
        }

        List<PageOutcome> outcomes = new ArrayList<PageOutcome>();
        for (PageInput page : exam.getPages()) {
            BufferedImage original = originals.get(page.getPageId());
            try {
                outcomes.add(published(page, original, bundle, processPage(exam, page, original, bundle)));
            } catch (RuntimeException e) {
                outcomes.add(manual(page, original, original, transform(original, original, 0),
                        "page_processing_error", bundle.groupByPage.get(page.getPageId()), null));
            }
        }
        return new ExamOutcome(exam.getExamId(), "processed", "ok", outcomes, bundle.groups);
    }

    private PageOutcome published(PageInput page, BufferedImage original, PatternBundle bundle, PageOutcome outcome) {
        if ("safe_to_erase".equals(outcome.getStatus()) || "no_pagenum".equals(outcome.getStatus())
                || "manual_review".equals(outcome.getStatus())) {
            return outcome;
        }
        return manual(page, original, outcome.getNormalized(), outcome.getTransforms(),
                "internal_state_" + outcome.getStatus(), bundle.groupByPage.get(page.getPageId()), outcome.getLocate());
    }

    private PatternBundle buildPattern(ExamInput exam, Map<String, BufferedImage> originals) {
        PatternBundle bundle = new PatternBundle();
        for (List<PageInput> batch : PageBatcher.overlapping(exam.getPages(), 8, 1)) {
            List<VlmClient.PageImage> images = new ArrayList<VlmClient.PageImage>();
            List<String> expected = new ArrayList<String>();
            for (PageInput page : batch) {
                images.add(new VlmClient.PageImage(page.getPageId(), originals.get(page.getPageId())));
                expected.add(page.getPageId());
            }
            PatternResponse response = vlm.pattern(images);
            if (!pageIds(response).equals(new java.util.HashSet<String>(expected))) {
                throw new ResponseParser.ParseException("batch page ids mismatch", "");
            }
            for (PageDirection direction : response.page_directions) {
                PageDirection existing = bundle.directions.get(direction.page_id);
                if (existing != null && (existing.reading_rotation != direction.reading_rotation
                        || Math.abs(existing.confidence - direction.confidence) > 0.20)) {
                    bundle.consensusState = "mixed";
                } else {
                    bundle.directions.put(direction.page_id, direction);
                }
            }
            for (PatternGroup group : response.pattern_groups) {
                bundle.groups.add(group);
                for (String pageId : group.page_ids) {
                    PatternGroup existing = bundle.groupByPage.get(pageId);
                    if (existing != null && !existing.group_id.equals(group.group_id)) {
                        bundle.consensusState = "mixed";
                    }
                    bundle.groupByPage.put(pageId, group);
                }
            }
        }
        return bundle;
    }

    private java.util.Set<String> pageIds(PatternResponse response) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (PageDirection direction : response.page_directions) {
            if (!ids.add(direction.page_id)) {
                throw new ResponseParser.ParseException("duplicate page_id", "");
            }
        }
        return ids;
    }

    private PageOutcome processPage(ExamInput exam, PageInput page, BufferedImage original, PatternBundle bundle) {
        PageDirection direction = bundle.directions.get(page.getPageId());
        if (direction == null || direction.confidence < MIN_DIRECTION_CONFIDENCE) {
            return manual(page, original, original, transform(original, original, direction == null ? 0 : direction.reading_rotation),
                    "low_direction_confidence", bundle.groupByPage.get(page.getPageId()), null);
        }

        OrientationNormalizer.NormalizedImage normalized = OrientationNormalizer.normalize(original, direction.reading_rotation);
        BufferedImage normalizedImage = normalized.getImage();
        PageTransforms transforms = new PageTransforms(normalized.getOriginalWidth(), normalized.getOriginalHeight(),
                normalized.getNormalizedWidth(), normalized.getNormalizedHeight(), normalized.getReadingRotation());
        PatternGroup group = bundle.groupByPage.get(page.getPageId());
        VlmClient.PageImage pageImage = new VlmClient.PageImage(page.getPageId(), normalizedImage);
        LocateResponse locate;
        try {
            locate = vlm.locate(pageImage, group);
        } catch (RuntimeException e) {
            return manual(page, original, normalizedImage, transforms, "locate_error", group, null);
        }
        if ("manual_review".equals(locate.status)) {
            return manual(page, original, normalizedImage, transforms, "locate_manual_review", group, locate);
        }
        if ("no_pagenum".equals(locate.status) || locate.regions.isEmpty()) {
            return handleNoCandidate(exam, page, original, normalizedImage, transforms, bundle, group, locate, pageImage);
        }

        RegionValidator.ValidationResult validation = RegionValidator.validate(
                new RegionValidator.PageLocateResult(locate.page_id, locate.status, locate.regions, locate.nearest_body_boundary),
                normalizedImage);
        if (!validation.isAccepted()) {
            return manual(page, original, normalizedImage, transforms, "validation_rejected", group, locate);
        }
        RiskGate.PageContext riskContext = RiskGate.PageContext.stable(page.getPageId())
                .withPatternGroupId(group == null ? null : group.group_id)
                .withConsensusState(bundle.consensusState)
                .withReadingRotation(direction.reading_rotation)
                .withPageSequenceIncomplete(exam.isPageSequenceIncomplete());
        boolean requiresVerify = RiskGate.requiresLocalVerify(riskContext, validation) || hasOnLineRegion(locate.regions);
        if (requiresVerify) {
            PageOutcome denied = verifyThenMaybeManual(page, original, normalizedImage, transforms, group, locate, pageImage, "verify_denied");
            if (!"needs_recheck".equals(denied.getStatus())) {
                return denied;
            }
        }
        return eraseAndAudit(page, original, normalizedImage, transforms, group, locate, pageImage, validation.getRegions());
    }

    private boolean hasOnLineRegion(List<EraseRegion> regions) {
        for (EraseRegion region : regions) {
            if (region.on_line) {
                return true;
            }
        }
        return false;
    }

    private PageOutcome handleNoCandidate(ExamInput exam, PageInput page, BufferedImage original, BufferedImage normalized,
                                          PageTransforms transforms, PatternBundle bundle, PatternGroup group,
                                          LocateResponse locate, VlmClient.PageImage pageImage) {
        if (group == null || "mixed".equals(bundle.consensusState)) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "locate_no_pagenum", original, normalized,
                    normalized, transforms, group, Collections.<EraseRegion>emptyList(), locate, null);
        }
        VerifyResponse verify = vlm.verify(pageImage, null,
                edgeRoi(page.getPageId(), group, locate.nearest_body_boundary, normalized));
        if ("no_pagenum".equals(verify.decision)) {
            return new PageOutcome(page.getPageId(), "no_pagenum", "edge_verify_no_pagenum", original, normalized,
                    normalized, transforms, group, Collections.<EraseRegion>emptyList(), locate, null);
        }
        return manual(page, original, normalized, transforms, "edge_verify_" + verify.decision, group, locate);
    }

    private PageOutcome verifyThenMaybeManual(PageInput page, BufferedImage original, BufferedImage normalized,
                                              PageTransforms transforms, PatternGroup group, LocateResponse locate,
                                              VlmClient.PageImage pageImage, String deniedReason) {
        for (EraseRegion region : locate.regions) {
            VerifyResponse verify = vlm.verify(pageImage, region,
                    roi(page.getPageId(), region, locate.nearest_body_boundary, normalized));
            if (!"safe_to_erase".equals(verify.decision)) {
                if ("no_pagenum".equals(verify.decision)) {
                    return new PageOutcome(page.getPageId(), "no_pagenum", "verify_no_pagenum", original, normalized,
                            normalized, transforms, group, Collections.<EraseRegion>emptyList(), locate, null);
                }
                return manual(page, original, normalized, transforms, deniedReason, group, locate);
            }
        }
        return new PageOutcome(page.getPageId(), "needs_recheck", "verify_safe", original, normalized, normalized,
                transforms, group, locate.regions, locate, null);
    }

    private PageOutcome eraseAndAudit(PageInput page, BufferedImage original, BufferedImage normalized, PageTransforms transforms,
                                      PatternGroup group, LocateResponse locate, VlmClient.PageImage pageImage,
                                      List<RegionValidator.PixelRegion> pixelRegions) {
        BufferedImage candidate = normalized;
        for (RegionValidator.PixelRegion pixelRegion : pixelRegions) {
            InkMaskEraser.EraseOutcome erase = InkMaskEraser.erase(candidate, pixelRegion);
            if (erase.getStatus() != InkMaskEraser.Status.SAFE_TO_ERASE) {
                return manual(page, original, normalized, transforms, "erase_failed: " + erase.getReason(), group, locate);
            }
            candidate = erase.getCandidate();
        }
        AuditResponse audit = vlm.audit(pageImage, new VlmClient.PageImage(page.getPageId(), candidate),
                locate.regions, rois(page.getPageId(), locate.regions, locate.nearest_body_boundary, normalized));
        if (!"pass".equals(audit.decision) || !audit.body_unchanged || !audit.target_removed || !audit.background_acceptable) {
            return new PageOutcome(page.getPageId(), "manual_review", "audit_failed", original, normalized,
                    normalized, transforms, group, locate.regions, locate, audit);
        }
        return new PageOutcome(page.getPageId(), "safe_to_erase", "audit_pass", original, normalized,
                candidate, transforms, group, locate.regions, locate, audit);
    }

    private VlmClient.RoiImage roi(String pageId, EraseRegion region, BodyBoundary boundary, BufferedImage image) {
        RoiTransform transform = RoiTransform.fromNormalizedCandidate(
                image.getWidth(), image.getHeight(), region, boundary, 24);
        return new VlmClient.RoiImage(pageId, region.region_id, crop(image, transform));
    }

    private VlmClient.RoiImage edgeRoi(String pageId, PatternGroup group, BodyBoundary boundary, BufferedImage image) {
        RoiTransform.PageEdge edge = RoiTransform.PageEdge.BOTTOM;
        if ("top".equals(group.edge)) {
            edge = RoiTransform.PageEdge.TOP;
        } else if ("left".equals(group.edge)) {
            edge = RoiTransform.PageEdge.LEFT;
        } else if ("right".equals(group.edge)) {
            edge = RoiTransform.PageEdge.RIGHT;
        }
        return new VlmClient.RoiImage(pageId, "edge",
                crop(image, RoiTransform.fromEdge(image.getWidth(), image.getHeight(), edge, boundary, 24)));
    }

    private List<VlmClient.RoiImage> rois(String pageId, List<EraseRegion> regions,
                                          BodyBoundary boundary, BufferedImage image) {
        List<VlmClient.RoiImage> rois = new ArrayList<VlmClient.RoiImage>();
        for (EraseRegion region : regions) {
            rois.add(roi(pageId, region, boundary, image));
        }
        return rois;
    }

    private BufferedImage crop(BufferedImage image, RoiTransform transform) {
        return image.getSubimage(transform.getX(), transform.getY(), transform.getWidth(), transform.getHeight());
    }

    private PageOutcome manual(PageInput page, BufferedImage original, BufferedImage normalized, PageTransforms transforms,
                               String reason, PatternGroup group, LocateResponse locate) {
        if (transforms == null) {
            transforms = transform(original, normalized, 0);
        }
        return new PageOutcome(page.getPageId(), "manual_review", reason, original, normalized, normalized,
                transforms, group, locate == null ? Collections.<EraseRegion>emptyList() : locate.regions, locate, null);
    }

    private PageTransforms transform(BufferedImage original, BufferedImage normalized, int readingRotation) {
        return new PageTransforms(original.getWidth(), original.getHeight(), normalized.getWidth(), normalized.getHeight(), readingRotation);
    }

    private ExamOutcome wholeExamFallback(ExamInput exam, Map<String, BufferedImage> originals, String reason, String detail) {
        List<PageOutcome> pages = new ArrayList<PageOutcome>();
        for (PageInput page : exam.getPages()) {
            BufferedImage image = originals.get(page.getPageId());
            pages.add(new PageOutcome(page.getPageId(), "manual_review", detail, image, image, image,
                    transform(image, image, 0), null, Collections.<EraseRegion>emptyList(), null, null));
        }
        return new ExamOutcome(exam.getExamId(), "manual_review", reason, pages, Collections.<PatternGroup>emptyList());
    }

    private Map<String, BufferedImage> readOriginals(ExamInput exam) {
        Map<String, BufferedImage> images = new HashMap<String, BufferedImage>();
        for (PageInput page : exam.getPages()) {
            try {
                images.put(page.getPageId(), ImageIO.read(page.getImagePath().toFile()));
            } catch (IOException e) {
                throw new RuntimeException("cannot read page image: " + page.getPageId(), e);
            }
        }
        return images;
    }

    private static final class PatternBundle {
        final Map<String, PageDirection> directions = new HashMap<String, PageDirection>();
        final Map<String, PatternGroup> groupByPage = new HashMap<String, PatternGroup>();
        final List<PatternGroup> groups = new ArrayList<PatternGroup>();
        String consensusState = "stable";
    }

    public static final class RunContext {
    }
}
