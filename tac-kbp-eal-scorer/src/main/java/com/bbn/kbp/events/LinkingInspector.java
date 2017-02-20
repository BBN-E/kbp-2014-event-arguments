package com.bbn.kbp.events;

import com.bbn.bue.common.TextGroupImmutable;
import com.bbn.bue.common.evaluation.BootstrapInspector;
import com.bbn.bue.common.evaluation.BootstrapWriter;
import com.bbn.bue.common.evaluation.EvalPair;
import com.bbn.bue.common.math.PercentileComputer;
import com.bbn.bue.common.symbols.Symbol;
import com.bbn.kbp.events2014.TACKBPEALException;
import com.bbn.kbp.linking.ExplicitFMeasureInfo;
import com.bbn.kbp.linking.LinkF1;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.transform;

final class LinkingInspector implements
    BootstrapInspector.BootstrapStrategy<EvalPair<DocLevelArgLinking, DocLevelArgLinking>, LinkingInspector.DocLevelLinkingScoring> {
  private static final Logger log = LoggerFactory.getLogger(LinkingInspector.class);

  private final File outputDir;

  private LinkingInspector(final File outputDir) {
    this.outputDir = outputDir;
  }

  public static LinkingInspector createOutputtingTo(final File outputFile) {
    return new LinkingInspector(outputFile);
  }

  @Override
  public BootstrapInspector.ObservationSummarizer<EvalPair<DocLevelArgLinking, DocLevelArgLinking>, DocLevelLinkingScoring> createObservationSummarizer() {
    return new BootstrapInspector.ObservationSummarizer<EvalPair<DocLevelArgLinking, DocLevelArgLinking>, DocLevelLinkingScoring>() {
      @Override
      public DocLevelLinkingScoring summarizeObservation(
          final EvalPair<DocLevelArgLinking, DocLevelArgLinking> item) {

        final Symbol keyDocId = item.key().docID();
        final Symbol testDocId = item.test().docID();

        checkArgument(ImmutableSet.copyOf(concat(item.key())).containsAll(
            ImmutableSet.copyOf(concat(item.test()))), "Must contain only answers in test set!");
        if (!keyDocId.equalTo(testDocId)) {
          log.warn("DocIDs do not match: {} vs {}", keyDocId, testDocId);
        }

        final LinkingScoreDocRecord.Builder linkingScoreDocRecordB =
            new LinkingScoreDocRecord.Builder();
        final ImmutableMap.Builder<Symbol, LinkingScoreDocRecord> recordsPerEventB =
            ImmutableMap.builder();

        // for all events
        {
          final ExplicitFMeasureInfo counts = LinkF1.create().score(item.test(), item.key());

          final File docOutput = new File(outputDir, keyDocId.asString());
          docOutput.mkdirs();
          final PrintWriter outputWriter;
          try {
            outputWriter = new PrintWriter(new File(docOutput, "linkingF.txt"));
            outputWriter.println(counts.toString());
            outputWriter.close();
          } catch (FileNotFoundException e) {
            throw new TACKBPEALException(e);
          }

          final ImmutableSet<DocLevelEventArg> args = ImmutableSet.copyOf(concat(
              transform(concat(item.test().eventFrames(), item.key().eventFrames()),
                  ScoringEventFrameFunctions.arguments())));

          linkingScoreDocRecordB.fMeasureInfo(counts)
              .predictedCounts(ImmutableSet.copyOf(concat(item.test().eventFrames())).size())
              .actualCounts(ImmutableSet.copyOf(concat(item.key().eventFrames())).size())
              .linkingArgCounts(args.size());
        }
        // per event
        {
          // set of all event-types found in the doc
          final ImmutableSet<Symbol> eventTypes = FluentIterable
              .from(item.test().eventFrames())
              .append(item.key().eventFrames())
              .transform(ScoringEventFrameFunctions.eventType())
              .toSet();

          // create mapping of f-scores and counts per event-type
          for (final Symbol eventType : eventTypes) {

            final Predicate<DocLevelEventArg> argPred = Predicates
                .compose(Predicates.equalTo(eventType), DocLevelEventArgFunctions.eventType());
            DocLevelArgLinking filteredKey = item.key().filterArguments(argPred);
            DocLevelArgLinking filteredTest = item.test().filterArguments(argPred);

            final ExplicitFMeasureInfo countsForEventType =
                LinkF1.create().score(filteredTest, filteredKey);
            final ImmutableSet<DocLevelEventArg> argsForEventType = ImmutableSet.copyOf(concat(
                transform(concat(filteredTest.eventFrames(), filteredKey.eventFrames()),
                    ScoringEventFrameFunctions.arguments())));
            final LinkingScoreDocRecord recordForEventType = new LinkingScoreDocRecord.Builder()
                .fMeasureInfo(countsForEventType)
                .predictedCounts(ImmutableSet.copyOf(concat(filteredTest.eventFrames())).size())
                .actualCounts(ImmutableSet.copyOf(concat(filteredKey.eventFrames())).size())
                .linkingArgCounts(argsForEventType.size())
                .build();

            recordsPerEventB.put(eventType, recordForEventType);
          }
        }
        return new DocLevelLinkingScoring.Builder()
            .linkingScoreDocRecord(linkingScoreDocRecordB.build())
            .docRecordsPerEventType(recordsPerEventB.build())
            .build();
      }
    };
  }

  @Override
  public Collection<BootstrapInspector.SummaryAggregator<DocLevelLinkingScoring>> createSummaryAggregators() {
    return ImmutableList.<BootstrapInspector.SummaryAggregator<DocLevelLinkingScoring>>of(
        new BootstrapInspector.SummaryAggregator<DocLevelLinkingScoring>() {

          private final AggregateLinkingScoreRecord.Builder aggregateRecordB =
              new AggregateLinkingScoreRecord.Builder();
          private final ImmutableListMultimap.Builder<Symbol, AggregateLinkingScoreRecord>
              aggregateRecordsPerEventB = ImmutableListMultimap.builder();

          private static final String F1 = "F1";
          private static final String PRECISION = "Precision";
          private static final String RECALL = "Recall";

          private final BootstrapWriter writer = new BootstrapWriter.Builder()
              .measures(ImmutableList.of(F1, PRECISION, RECALL))
              .percentilesToPrint(
                  ImmutableList.of(0.005, 0.025, 0.05, 0.25, 0.5, 0.75, 0.95, 0.975, 0.995))
              .percentileComputer(PercentileComputer.nistPercentileComputer())
              .build();

          @Override
          public void observeSample(
              final Collection<DocLevelLinkingScoring> collection) {
            // copies logic from com.bbn.kbp.events2014.scorer.bin.AggregateResultWriter.computeLinkScores()

            // for all event types
            {
              double precision = 0.0;
              double recall = 0.0;
              double f1 = 0.0;
              double linkNormalizerSum = 0.0;

              final ImmutableListMultimap.Builder<String, Double> f1sB =
                  ImmutableListMultimap.builder();
              final ImmutableListMultimap.Builder<String, Double> precisionsB =
                  ImmutableListMultimap.builder();
              final ImmutableListMultimap.Builder<String, Double> recallsB =
                  ImmutableListMultimap.builder();

              for (final DocLevelLinkingScoring linkingScoring : collection) {
                LinkingScoreDocRecord docRecord = linkingScoring.linkingScoreDocRecord();
                precision += docRecord.fMeasureInfo().precision() * docRecord.predictedCounts();
                recall += docRecord.fMeasureInfo().recall() * docRecord.actualCounts();
                f1 += docRecord.fMeasureInfo().f1() * docRecord.actualCounts();
                linkNormalizerSum += docRecord.linkingArgCounts();
              }

              // the normalizer sum can't actually be negative here, but this minimizes divergence with the source logic.
              f1sB.put("Aggregate", (linkNormalizerSum > 0.0) ? f1 / linkNormalizerSum : 0.0);
              precisionsB.put("Aggregate", (linkNormalizerSum > 0.0) ? precision / linkNormalizerSum : 0.0);
              recallsB.put("Aggregate", (linkNormalizerSum > 0.0) ? recall / linkNormalizerSum : 0.0);

              aggregateRecordB.f1s(f1sB.build()).precisions(precisionsB.build())
                  .recalls(recallsB.build());
            }
            // per event type
            {
              // get all event-types that occurred in all docs (i.e. an aggregate event-type set)
              final ImmutableSet.Builder<Symbol> eventTypesB = ImmutableSet.builder();
              for (final DocLevelLinkingScoring linkingScoring : collection) {
                eventTypesB.addAll(linkingScoring.docRecordsPerEventType().keySet());
              }
              final ImmutableSet<Symbol> eventTypes = eventTypesB.build();

              // get aggregate f-measure info for each event-type
              for (final Symbol eventType : eventTypes) {

                double precision = 0.0;
                double recall = 0.0;
                double f1 = 0.0;
                double linkNormalizerSum = 0.0;

                final ImmutableListMultimap.Builder<String, Double> f1sB =
                    ImmutableListMultimap.builder();
                final ImmutableListMultimap.Builder<String, Double> precisionsB =
                    ImmutableListMultimap.builder();
                final ImmutableListMultimap.Builder<String, Double> recallsB =
                    ImmutableListMultimap.builder();

                for (final DocLevelLinkingScoring linkingScoring : collection) {
                  if (linkingScoring.docRecordsPerEventType().containsKey(eventType)) {
                    final LinkingScoreDocRecord record =
                        linkingScoring.docRecordsPerEventType().get(eventType);
                    precision += record.fMeasureInfo().precision() * record.predictedCounts();
                    recall += record.fMeasureInfo().recall() * record.actualCounts();
                    f1 += record.fMeasureInfo().f1() * record.linkingArgCounts();
                    linkNormalizerSum += record.linkingArgCounts();
                  }
                }

                f1sB.put("Aggregate", (linkNormalizerSum > 0.0) ? f1 / linkNormalizerSum : 0.0);
                precisionsB.put("Aggregate", (linkNormalizerSum > 0.0) ? precision / linkNormalizerSum : 0.0);
                recallsB.put("Aggregate", (linkNormalizerSum > 0.0) ? recall / linkNormalizerSum : 0.0);

                final AggregateLinkingScoreRecord aggregateRecordForEventType = new AggregateLinkingScoreRecord.Builder()
                    .f1s(f1sB.build()).precisions(precisionsB.build()).recalls(recallsB.build()).build();

                aggregateRecordsPerEventB.put(eventType, aggregateRecordForEventType);
              }

            }
          }

          //@Override
          public void finish() throws IOException {
            // for all events
            final AggregateLinkingScoreRecord aggregateRecord = aggregateRecordB.build();
            writer.writeBootstrapData("linkScores",
                ImmutableMap.of(
                    F1, aggregateRecord.f1s(),
                    PRECISION, aggregateRecord.precisions(),
                    RECALL, aggregateRecord.recalls()
                ),
                new File(outputDir, "linkScores"));

            // per event
            final ImmutableListMultimap aggregateRecordsPerEvent = aggregateRecordsPerEventB.build();
            // TODO: write-out per event-type
          }
        });
  }

  @TextGroupImmutable
  @Value.Immutable
  abstract static class DocLevelLinkingScoring {

    abstract LinkingScoreDocRecord linkingScoreDocRecord();

    abstract ImmutableMap<Symbol, LinkingScoreDocRecord> docRecordsPerEventType();

    public static class Builder extends ImmutableDocLevelLinkingScoring.Builder {

    }
  }

  @TextGroupImmutable
  @Value.Immutable
  abstract static class LinkingScoreDocRecord {

    abstract ExplicitFMeasureInfo fMeasureInfo();

    abstract int predictedCounts();

    abstract int actualCounts();

    abstract int linkingArgCounts();

    public static class Builder extends ImmutableLinkingScoreDocRecord.Builder {

    }
  }

  @TextGroupImmutable
  @Value.Immutable
  abstract static class AggregateLinkingScoreRecord {

    abstract  ImmutableListMultimap<String, Double> f1s();

    abstract ImmutableListMultimap<String, Double> precisions();

    abstract ImmutableListMultimap<String, Double> recalls();

    public static class Builder extends ImmutableAggregateLinkingScoreRecord.Builder {

    }
  }
}
