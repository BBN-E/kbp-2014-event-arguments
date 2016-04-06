package com.bbn.kbp.events2014.io;


import com.bbn.bue.common.symbols.Symbol;
import com.bbn.kbp.events2014.CorefAnnotation;
import com.bbn.kbp.events2014.CorpusEventFrame;
import com.bbn.kbp.events2014.CorpusEventLinking;
import com.bbn.kbp.events2014.DocEventFrameReference;
import com.bbn.kbp.events2014.DocumentSystemOutput;
import com.bbn.kbp.events2014.DocumentSystemOutput2015;
import com.bbn.kbp.events2014.Response;
import com.bbn.kbp.events2014.ResponseFunctions;
import com.bbn.kbp.events2014.ResponseLinking;
import com.bbn.kbp.events2014.ResponseSet;
import com.bbn.kbp.events2014.SystemOutputLayout;
import com.bbn.kbp.events2014.TypeRoleFillerRealis;
import com.bbn.kbp.events2014.TypeRoleFillerRealisSet;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getFirst;

public final class SystemOutputLayoutTest {

  private static final Logger log = LoggerFactory.getLogger(SystemOutputLayoutTest.class);

  @Test
  public void test2016LayoutReading() throws IOException {
    final SystemOutputStore2016 outputStore =
        (SystemOutputStore2016) SystemOutputLayout.KBP_EA_2016
            .open(new File(this.getClass().getResource("/com/bbn/kbp/events2014/io/CorpusEventTest")
                .getFile()));
    for (final Symbol docID : outputStore.docIDs()) {
      final DocumentSystemOutput2015 store2015 = outputStore.read(docID);
      checkNotNull(store2015.arguments());
      checkState(store2015.arguments().responses().size() > 0);
      checkNotNull(store2015.linking());
      checkState(store2015.linking().responseSets().size() > 0);
    }
    checkState(outputStore.readCorpusEventFrames().corpusEventFrames().size() > 0);
  }

  private void create2016LayoutTest() throws IOException {
    // this must point to a directory via your filesystem, not a resource, unless you want to debug.
    // what are you doing here anyway?
    final File dir = new File(
        SystemOutputLayoutTest.class
            .getResource("/com/bbn/kbp/events2014/io/CorpusEventTest/")
            .getFile()
    );

    final SystemOutputStore sourceLayout = SystemOutputLayout.KBP_EA_2015.open(dir);
    final ImmutableMap.Builder<String, TypeRoleFillerRealisSet> completeTRFRMap =
        ImmutableMap.builder();

    for (final Symbol docID : sourceLayout.docIDs()) {
      log.info("Processing {}", docID);
      final DocumentSystemOutput answerKey = sourceLayout.read(docID);

      // coref everything in the same document
      final ImmutableSet<Response> responses = answerKey.arguments().responses();
      final ImmutableMultimap<Symbol, Response> typeToResponse =
          FluentIterable.from(responses).index(
              ResponseFunctions.type());
      final ResponseLinking.Builder responseLinkingB = ResponseLinking.builder().docID(docID);
      for (final Symbol type : typeToResponse.keySet()) {
        checkState(typeToResponse.get(type).size() > 0);
        responseLinkingB.addResponseSets(ResponseSet.of(typeToResponse.get(type)));
      }
      final ResponseLinking responseLinking = responseLinkingB.build();
      log.info("ResponseLinking size is {}", responseLinking.responseSets().size());

      final DocumentSystemOutput2015 documentSystemOutput2015 = DocumentSystemOutput2015
          .from(answerKey.arguments(), responseLinking);
      sourceLayout.write(documentSystemOutput2015);

      final ImmutableMultimap<TypeRoleFillerRealis, Response> trfrs = FluentIterable.from(responses)
          .index(TypeRoleFillerRealis
              .extractFromSystemResponse(
                  CorefAnnotation.laxBuilder(docID).build().laxCASNormalizerFunction()));
      final ImmutableMap.Builder<String, TypeRoleFillerRealisSet> idMapB = ImmutableMap.builder();
      for (final TypeRoleFillerRealis trfr : trfrs.keySet()) {
        final TypeRoleFillerRealisSet trfrSet =
            TypeRoleFillerRealisSet.create(ImmutableSet.of(trfr));
        idMapB.put(trfr.uniqueIdentifier(), trfrSet);
      }
      completeTRFRMap.putAll(idMapB.build());
    }
    sourceLayout.close();

    // this should really point to the same place as used above in the event of regenerating the example output.
    final SystemOutputStore2016 outputStore =
        (SystemOutputStore2016) SystemOutputLayout.KBP_EA_2016
            .openOrCreate(new File(
                this.getClass().getResource("/com/bbn/kbp/events2014/io/CorpusEventTest")
                    .getFile())
            );

    final CorpusEventLinking.Builder corpusEventLinkingB = CorpusEventLinking.builder();
    final ImmutableMap<String, TypeRoleFillerRealisSet> hopperIDs =
        completeTRFRMap.build();
    for (final String id : hopperIDs.keySet()) {
      corpusEventLinkingB.addCorpusEventFrames(CorpusEventFrame.of(id,
          ImmutableSet.of(DocEventFrameReference
              .of(checkNotNull(getFirst(hopperIDs.get(id), null)).docID(), id))));
    }
    checkState(hopperIDs.size() > 0);
    outputStore.writeCorpusEventFrames(corpusEventLinkingB.build());

    outputStore.close();


  }

}