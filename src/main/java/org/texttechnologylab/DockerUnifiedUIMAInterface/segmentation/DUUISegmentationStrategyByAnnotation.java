package org.texttechnologylab.DockerUnifiedUIMAInterface.segmentation;

import org.apache.uima.UIMAException;
import org.apache.uima.cas.Type;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.TOP;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.CasCopier;
import org.apache.uima.util.TypeSystemUtil;
import org.jetbrains.annotations.NotNull;
import org.texttechnologylab.annotation.AnnotationComment;

import java.util.*;
import java.util.stream.Collectors;

/***
 * Automatic simple document segmentation by annotation type.
 */
public class DUUISegmentationStrategyByAnnotation extends DUUISegmentationStrategy {
    // Name to store needed meta infos in cas
    static public final String DUUI_SEGMENTED_REF = "__textimager_duui_segmented_ref__";
    static public final String DUUI_SEGMENTED_POS = "__textimager_duui_segmented_pos__";

    // Annotation type to use for splitting cas contents
    protected Class<? extends Annotation> SegmentationClass = null;

    // Max number of annotations (eg sentences) per segments
    // TODO update amount
    public static final int MAX_ANNOTATIONS_PER_SEGMENT_DEFAULT = 2;
    protected int maxAnnotationsPerSegment = MAX_ANNOTATIONS_PER_SEGMENT_DEFAULT;

    // Max number of characters per segment
    // TODO update amount
    public static final int MAX_CHARS_PER_SEGMENT_DEFAULT = 100;
    protected int maxCharsPerSegment = MAX_CHARS_PER_SEGMENT_DEFAULT;

    // Ignore missing annotations and just use full document
    public static final boolean IGNORE_MISSING_ANNOTATIONS_DEFAULT = false;
    protected boolean ignoreMissingAnnotations = IGNORE_MISSING_ANNOTATIONS_DEFAULT;

    // Current list of annotations
    private List<? extends Annotation> annotations;

    // Type system of the input cas
    private TypeSystemDescription typeSystemDescription;

    public DUUISegmentationStrategyByAnnotation withSegmentationClass(Class<? extends Annotation> clazz) {
        this.SegmentationClass = clazz;
        return this;
    }

    public DUUISegmentationStrategyByAnnotation withMaxAnnotationsPerSegment(int maxAnnotationsPerSegment) {
        this.maxAnnotationsPerSegment = maxAnnotationsPerSegment;
        return this;
    }

    public DUUISegmentationStrategyByAnnotation withMaxCharsPerSegment(int maxCharsPerSegment) {
        this.maxCharsPerSegment = maxCharsPerSegment;
        return this;
    }

    public DUUISegmentationStrategyByAnnotation withIgnoreMissingAnnotations(boolean ignoreMissingAnnotations) {
        this.ignoreMissingAnnotations = ignoreMissingAnnotations;
        return this;
    }

    @Override
    protected void initialize() throws UIMAException {
        // Type must have been set
        if (SegmentationClass == null) {
            throw new IllegalArgumentException("No annotation type for CAS segmentation provided, add using \"withSegmentationClass\".");
        }

        // Get the annotation type to segment the document, we expect it to be available in the cas
        annotations = new ArrayList<>(JCasUtil.select(jCasInput, SegmentationClass));
        if (annotations.isEmpty()) {
            if (!ignoreMissingAnnotations) {
                throw new IllegalArgumentException("No annotations of type \"" + SegmentationClass.getCanonicalName() + "\" for CAS segmentation found!");
            }
            else {
                System.err.println("No annotations of type \"" + SegmentationClass.getCanonicalName() + "\" for CAS segmentation found!");
                System.err.println("Running without segmentation, this might take a while.");
            }
        }
        else {
            System.out.println("Found " + annotations.size() + " annotations of type \"" + SegmentationClass.getCanonicalName() + "\" for CAS segmentation.");
        }

        // Copy original cas's typesystem to use for new cas
        typeSystemDescription = TypeSystemUtil.typeSystem2TypeSystemDescription(jCasInput.getTypeSystem());

        // Prepare output cas by copying the full input cas as base
        // Note that the output cas is created at initialization and not when starting the segmentation iterator
        jCasOutput = JCasFactory.createJCas(typeSystemDescription);
        CasCopier.copyCas(jCasInput.getCas(), jCasOutput.getCas(), true);
    }

    class DUUISegmentationStrategyByAnnotationIterator implements Iterator<JCas> {
        // Current annotation to consider
        private final ListIterator<? extends Annotation> annotationIt;
        private long annotationCount = 0;

        // Current and next segmented JCas
        private final JCas jCasCurrentSegment;
        private final JCas jCasNextSegment;

        private boolean hasMore = true;

        DUUISegmentationStrategyByAnnotationIterator()  {
            // Start with first annotation
            annotationIt = DUUISegmentationStrategyByAnnotation.this.annotations.listIterator();
            annotationCount = 0;

            try {
                // Create the segmented jCas, only create one as it is a slow operation
                jCasNextSegment = JCasFactory.createJCas(typeSystemDescription);
                jCasCurrentSegment = JCasFactory.createJCas(typeSystemDescription);
            } catch (UIMAException e) {
                throw new RuntimeException(e);
            }

            // Check for first segment to allow iteration
            nextSegment();
        }

        /***
         * Check, if a given annotation can be added to the current segment.
         * @param segmentCount Amount of annotations already in the current segment
         * @param segmentBegin Position begin of the current segment
         * @param annotationEnd Position end of the annotation to add
         * @return true, if the annotation can be added to the current segment, else false
         */
        boolean tryAddToSegment(int segmentCount, int segmentBegin, int annotationEnd) {
            // Can only add, if below limit for annotations per segment
            if (segmentCount >= maxAnnotationsPerSegment) {
                return false;
            }

            // Can only add, if below char limit
            int segmentLength = annotationEnd - segmentBegin;
            if (segmentLength >= maxCharsPerSegment) {
                // Handle special case if even a single annotation is too long
                if (segmentCount == 0) {
                    System.err.println("Warning: The annotation is too long with " + segmentLength + " characters, which is over the specified limit of " + maxCharsPerSegment + ".");
                    return true;
                }
                return false;
            }

            // Below all limits, add this to current segment
            return true;
        }

        /***
         * Create a new CAS for this segment of the document.
         * @param segmentBegin The begin position of the segment
         * @param segmentEnd The end position of the segment
         * @return The new CAS segment
         */
        void createSegment(int segmentBegin, int segmentEnd) {
            // Note we do not handle errors here as this should normally not fail as annotations should not exceed the
            // document length, if it does, there might be something wrong and we fail early
            String documentText = jCasInput.getDocumentText().substring(segmentBegin, segmentEnd);

            // Reset next cas, faster than creating a new one
            jCasNextSegment.reset();
            CasCopier copierNext = new CasCopier(jCasInput.getCas(), jCasNextSegment.getCas());

            // Save begin of this segment to allow merging later
            // Note that we try to minimize the amount of data stored outside the cas to reduce complexity on merging
            // and make multi-threading easier
            AnnotationComment commentPos = new AnnotationComment(jCasNextSegment);
            commentPos.setKey(DUUI_SEGMENTED_POS);
            commentPos.setValue(String.valueOf(segmentBegin));
            commentPos.addToIndexes();

            // Copy all annotations with position in the segment bounds and all without positions, as we do not know
            // wheather they are needed by the tool or not
            for (TOP annotation : JCasUtil.select(jCasInput, TOP.class)) {
                boolean hasPosition = false;
                if (annotation instanceof Annotation) {
                    hasPosition = true;
                    // Make sure annotation is in segment bounds
                    Annotation positionAnnotation = (Annotation) annotation;
                    if (!(positionAnnotation.getBegin() >= segmentBegin && positionAnnotation.getEnd() <= segmentEnd)) {
                        continue;
                    }
                }

                // Annotation either has no position, or is in segment bounds
                TOP copy = (TOP) copierNext.copyFs(annotation);
                if (hasPosition) {
                    // Shift begin and end to segment
                    Annotation positionCopy = (Annotation) copy;
                    positionCopy.setBegin(positionCopy.getBegin() - segmentBegin);
                    positionCopy.setEnd(positionCopy.getEnd() - segmentBegin);
                }
                copy.addToIndexes(jCasNextSegment);

                // Mark this annotations as copied, this allows us to ignore it on merging
                AnnotationComment commentId = new AnnotationComment(jCasNextSegment);
                commentId.setKey(DUUI_SEGMENTED_REF);
                commentId.setReference(copy);
                commentId.setValue(String.valueOf(annotation.getAddress()));
                commentId.addToIndexes();
            }

            // Add relevant document text and language
            jCasNextSegment.setDocumentLanguage(jCasInput.getDocumentLanguage());
            jCasNextSegment.setDocumentText(documentText);

            // TODO allow to switch off statistics printing and use logging library!
            Collection<TOP> allNewAnnotations = JCasUtil.select(jCasNextSegment, TOP.class);
            Map<Type, Long> allNewAnnotationsCounts = allNewAnnotations
                    .stream()
                    .collect(Collectors
                            .groupingByConcurrent(TOP::getType, Collectors.counting())
                    );
            System.out.println("Created new CAS segment with " + allNewAnnotations.size() + " annotations from " + segmentBegin + " to " + segmentEnd + ".");
            for (Map.Entry<Type, Long> entry : allNewAnnotationsCounts.entrySet()) {
                System.out.println("  " + entry.getKey().getShortName() + ": " + entry.getValue());
            }
        }

        void nextSegment() {
            // The max amount should not change as we rely on list created at initialization
            // However, as we take also all annotations withut positions, the data can still grow much larger,
            // thus we do not write directly in the provided input cas but rely on a separate output cas
            System.out.println("Processed " + annotationCount + "/" + annotations.size() + " annotations of type \"" + SegmentationClass.getCanonicalName() + "\" for CAS segmentation.");

            // Assume we have no more segments, as we "look into the future"
            hasMore = false;

            // Check all annotations that were discovered at initialization by using an iterator
            // which allows us to step back if we need to continue with the current annotation in the next segment
            List<Annotation> currentSegment = new ArrayList<>();
            while (annotationIt.hasNext()) {
                Annotation annotation = annotationIt.next();
                annotationCount++;

                // Get begin/end of current segment to align to the exact boundaries
                int segmentBegin = annotation.getBegin();
                int segmentEnd = annotation.getEnd();
                if (!currentSegment.isEmpty()) {
                    segmentBegin = currentSegment.get(0).getBegin();
                    segmentEnd = currentSegment.get(currentSegment.size()-1).getEnd();
                }

                // Try adding as many annotations as possible to the current segment
                boolean canAdd = tryAddToSegment(currentSegment.size(), segmentBegin, annotation.getEnd());

                // Create CAS from segment if over limit and start new segment
                if (!canAdd) {
                    createSegment(segmentBegin, segmentEnd);
                    currentSegment.clear();

                    // Step back to continue with this annotation in the next segment
                    annotationIt.previous();
                    annotationCount--;

                    // We have more segments later, stop here
                    hasMore = true;
                    return;
                }
                else {
                    // Ok, just add to current segment
                    currentSegment.add(annotation);
                }
            }

            // If there are annotations left in the current segment after finishing the iteration,
            // create a final segment without checking the limits
            if (!currentSegment.isEmpty()) {
                int segmentBegin = currentSegment.get(0).getBegin();
                int segmentEnd = currentSegment.get(currentSegment.size()-1).getEnd();
                createSegment(segmentBegin, segmentEnd);

                // We have more segments
                hasMore = true;
            }
        }

        @Override
        public boolean hasNext() {
            return hasMore;
        }

        @Override
        public JCas next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            // Switch next to current segment using a full copy
            jCasCurrentSegment.reset();
            CasCopier.copyCas(jCasNextSegment.getCas(), jCasCurrentSegment.getCas(), true);

            // Try to create the next segment to update the hasMore flag
            nextSegment();

            // Return the current segment for processing
            return jCasCurrentSegment;
        }
    }

    @NotNull
    @Override
    public Iterator<JCas> iterator() {
        return new DUUISegmentationStrategyByAnnotationIterator();
    }

    /***
     * Recombine the segments back into the output cas
     * Note that this should rely only on the given segment cas to allow for parallelization later
     * @param jCasSegment
     */
    @Override
    public void merge(JCas jCasSegment) {
        // Copy to output cas
        CasCopier copier = new CasCopier(jCasSegment.getCas(), jCasOutput.getCas());

        // Collect all annotations that were prevoiusly copied and thus are not new
        Map<TOP, List<AnnotationComment>> copiedIds = JCasUtil
                .select(jCasSegment, AnnotationComment.class)
                .stream()
                .filter(c -> c.getKey().equals(DUUI_SEGMENTED_REF))
                .collect(Collectors.groupingBy(AnnotationComment::getReference));

        // Find segment begin position for reindexing, use 0 as
        // TODO we probably should fail here, as this might indicate a problem with the segmentation or the
        //  processing tool, however this might be useful for some cases?
        int segmentBegin = JCasUtil
                .select(jCasSegment, AnnotationComment.class)
                .stream()
                .filter(c -> c.getKey().equals(DUUI_SEGMENTED_POS))
                .map(c -> Integer.parseInt(c.getValue()))
                .findFirst()
                .orElse(0);

        // Copy newly generated annotations
        Collection<TOP> annotations = JCasUtil.select(jCasSegment, TOP.class);
        long annotationCount = annotations.size();
        long oldCounter = 0;
        long copiedCounter = 0;
        long deletedCounter = 0;
        for (TOP annotation : annotations) {
            // Only copy newly generated annotations
            if (copiedIds.containsKey(annotation)) {
                oldCounter++;
                continue;
            }

            // Also check if this is an internal "meta annotation"
            if (annotation instanceof AnnotationComment) {
                AnnotationComment comment = (AnnotationComment) annotation;
                if (comment.getKey().equals(DUUI_SEGMENTED_POS) || comment.getKey().equals(DUUI_SEGMENTED_REF)) {
                    deletedCounter++;
                    continue;
                }
            }

            // This is a new annotation, copy
            TOP copy = (TOP) copier.copyFs(annotation);
            boolean hasPosition = (annotation instanceof Annotation);
            if (hasPosition) {
                // Shift begin and end back to original
                Annotation positionCopy = (Annotation) copy;
                positionCopy.setBegin(positionCopy.getBegin() + segmentBegin);
                positionCopy.setEnd(positionCopy.getEnd() + segmentBegin);
            }
            copy.addToIndexes(jCasOutput);
            copiedCounter++;
        }

        // TODO allow disabling of statistics
        boolean seemsOk = annotationCount - copiedCounter - deletedCounter - oldCounter == 0;
        System.out.println("Merging " + annotationCount + " annotations: " + (seemsOk ? "OK" : "ERROR"));
        System.out.println(" New:\t" + copiedCounter + " (" + (copiedCounter * 100 / annotationCount) + "%)");
        System.out.println(" Meta:\t" + deletedCounter + " (" + (deletedCounter * 100 / annotationCount) + "%)");
        System.out.println(" Old:\t" + oldCounter + " (" + (oldCounter * 100 / annotationCount) + "%)");
    }
}
