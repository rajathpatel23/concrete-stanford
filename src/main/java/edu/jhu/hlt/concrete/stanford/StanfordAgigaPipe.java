package edu.jhu.hlt.concrete.stanford;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.jhu.agiga.AgigaDocument;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.EntitySet;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.SectionSegmentation;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.communications.SuperCommunication;
import edu.jhu.hlt.concrete.util.ConcreteException;
import edu.jhu.hlt.concrete.util.ThriftIO;
import edu.stanford.nlp.ling.CoreAnnotations.CharacterOffsetBeginAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.CharacterOffsetEndAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentenceIndexAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokenBeginAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokenEndAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.util.CoreMap;

public class StanfordAgigaPipe {

  private static final Logger logger = LoggerFactory.getLogger(StanfordAgigaPipe.class);

  static final String usage = "You must specify an input path: java edu.jhu.hlt.concrete.stanford.StanfordAgigaPipe --input path/to/input/file --output path/to/output/file\n"
      + "  Optional arguments: \n"
      + "       --annotate-sections <comma-separated-list of type names> (default: PASSAGE)\n"
      + "       --debug\n\t\tto print debugging messages (default: false)\n";

  private int sentenceCount = 1; // for flat files, no document structure

  // private boolean aggregateSectionsByFirst = false;
  // private boolean tokenize = true;
  // private boolean parse = false;

  private InMemoryAnnoPipeline pipeline;
  private Set<String> annotateNames;

  private int charOffset = 0;
  private int globalTokenOffset = 0;

  public static void main(String[] args) throws TException, IOException, ConcreteException {
    if (args.length != 2) {
      System.out.println("Usage: " + StanfordAgigaPipe.class.getSimpleName() + " <input-concrete-file-with-section-segmentations> <output-file-name>");
      System.exit(1);
    }

    final String inputPath = args[0];
    final String outputPath = args[1];
    final Communication communication = ThriftIO.readFile(inputPath);

    // this is silly, but needed for stanford logging disable.
    PrintStream err = System.err;

    System.setErr(new PrintStream(new OutputStream() {
      public void write(int b) { }
    }));

    StanfordAgigaPipe sap = new StanfordAgigaPipe();
    logger.info("Beginning annotation.");
    Communication annotated = sap.process(communication);
    logger.info("Finished.");
    System.setErr(err);

    new SuperCommunication(annotated).writeToFile(outputPath, true);
  }

//  public StanfordAgigaPipe(String[] args) {
//    annotateNames = new HashSet<SectionKind>();
//    parseArgs(args);
//    if (inputFile == null || outputFile == null) {
//      logger.info(usage);
//      System.exit(1);
//    }
//
//    pipeline = new InMemoryAnnoPipeline();
//  }

  public StanfordAgigaPipe() {
    annotateNames = new HashSet<>();
    annotateNames.add("Passage");
    annotateNames.add("Other");
    pipeline = new InMemoryAnnoPipeline();
  }

  public StanfordAgigaPipe(Set<String> typesToAnnotate) {
    this.annotateNames = new HashSet<>();
    this.annotateNames.addAll(typesToAnnotate);
    this.pipeline = new InMemoryAnnoPipeline();
  }

//  public void parseArgs(String[] args) {
//    int i = 0;
//    boolean userAddedAnnotations = false;
//    try {
//      while (i < args.length) {
//        if (args[i].equals("--annotate-sections")) {
//          String[] toanno = args[++i].split(",");
//          for (String t : toanno)
//            annotateNames.add(SectionKind.valueOf(t));
//          userAddedAnnotations = true;
//        }
//        // if(args[i].equals("--aggregate-by-first-section-number"))
//        // aggregateSectionsByFirst = args[++i].equals("t");
//        else if (args[i].equals("--input"))
//          inputFile = args[++i];
//        else if (args[i].equals("--output"))
//          outputFile = args[++i];
//        else {
//          logger.debug("Invalid option: " + args[i]);
//          logger.debug(usage);
//          System.exit(1);
//        }
//        i++;
//      }
//    } catch (Exception e) {
//      logger.debug(usage);
//      System.exit(1);
//    }
//    if (!userAddedAnnotations)
//      annotateNames.add(SectionKind.PASSAGE);
//  }

  public Communication process(Communication c) throws TException, IOException, ConcreteException {
    if (!c.isSetText())
      throw new ConcreteException("Expecting Communication Text, but was empty or none.");
    else if (!c.isSetSectionSegmentations() || c.getSectionSegmentationsSize() == 0)
      throw new ConcreteException("Expecting Section Segmentations, but there weren't any.");
    else {
      SectionSegmentation ss = c.getSectionSegmentationsIterator().next();
      if (!ss.isSetSectionList() || ss.getSectionListSize() == 0)
        throw new ConcreteException("Expecting Sections, but there weren't any.");
    }

    // cp will be heavily mutated here.
    Communication cp = new Communication(c);

    runPipelineOnCommunicationSectionsAndSentences(cp);
    return cp;
  }

  /**
   * Construct a dummy Annotation object that will serve as an aggregator. The properties SentencesAnnotation.class and TokensAnnotation.class are initialized
   * with lists of CoreMap and CoreLabel objects, respectively.
   */
  private Annotation getSeededDocumentAnnotation() {
    Annotation documentAnnotation = new Annotation("");
    documentAnnotation.set(SentencesAnnotation.class, new ArrayList<CoreMap>());
    documentAnnotation.set(TokensAnnotation.class, new ArrayList<CoreLabel>());
    return documentAnnotation;
  }

  /**
   * This steps through the given communication. For each section segmentation, it will go through each of the sections, first doing what localized processing
   * it can (i.e., all but coref resolution), and then doing the global processing (coref).
   */
  public void runPipelineOnCommunicationSectionsAndSentences(Communication comm) {

    // if called multiple times, reset the sentence count
    sentenceCount = 1;

    String commText = comm.getText();
    // List<Annotation> finishedAnnotations = new ArrayList<Annotation>();

    logger.debug("Annotating communication: {}", comm.getId());
    logger.debug("\tuuid = " + comm.getUuid());
    logger.debug("\ttype = " + comm.getType());
    logger.debug("\tfull = " + commText);
    for (SectionSegmentation sectionSegmentation : comm.getSectionSegmentations()) {
      // TODO: get section and sentence segmentation info from metadata
      List<Section> sections = sectionSegmentation.getSectionList();
      List<String> sectionUUIDs = new ArrayList<String>();
      // List<Integer> numberOfSentences = new ArrayList<Integer>();
      List<Tokenization> tokenizations = new ArrayList<Tokenization>();
      Annotation documentAnnotation = getSeededDocumentAnnotation();
      logger.debug("documentAnnotation = " + documentAnnotation);
      logger.debug("Annotating SectionSegmentation: {}", sectionSegmentation.getUuid());
      for (Section section : sections) {
        TextSpan sts = section.getTextSpan();
        // 1) First *perform* the tokenization & sentence splits
        // Note we do this first, even before checking the content-type
        String sectionText = commText.substring(sts.getStart(), sts.getEnding());
        Annotation a = pipeline.splitAndTokenizeText(sectionText);
        logger.debug("Annotating Section: {}", section.getUuid());
        logger.debug("\ttext = " + sectionText);
        logger.debug("\tkind = ");
        logger.debug(section.getKind() + " in annotateNames: " + annotateNames);
        if (!annotateNames.contains(section.getKind())) {
          // We MUST update the character offset
          charOffset += sectionText.length();
          // NOTE: It's possible we want to account for sentences in non-contentful sections
          // If that's the case, then we need to update the globalToken and sentence offset
          // variables correctly.
          if (a == null)
            continue;

          // List<CoreLabel> sentTokens = a.get(TokensAnnotation.class);
          // int tokenEnd = tokenOffset + sentTokens.size();
          // sentAnno.set(SentenceIndexAnnotation.class, sentIndex);
          // sentIndex++;
          // sentenceCount++;
          // tokenOffset = tokenEnd;
          // document.set(TokensAnnotation.class, docTokens);

          continue;
        }
        sectionUUIDs.add(section.getUuid());
        // 2) Second, perform the other localized processing
        logger.debug("Additional processing on section: {}", section.getUuid());
        processSection(section, a, documentAnnotation, tokenizations);
      }

      // 3) Third, do coref; cross-reference against sectionUUIDs
      logger.debug("Running coref.");
      processCoref(comm, documentAnnotation, tokenizations);
    }
  }

  /**
   * On a given annotation object representing a single section, run
   * <ul>
   * <li>part-of-speech tagging,</li>
   * <li>lemmatization,</li>
   * <li>constituency and dependency parsing, and</li>
   * <li>named entity recognition.</li>
   * </ul>
   * Note that corefence resolution is done only once all contentful sections have been properly annotated.
   *
   */
  public AgigaDocument annotate(Annotation annotation) {
    try {
      return pipeline.annotate(annotation);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public AgigaDocument annotateCoref(Annotation annotation) {
    try {
      return pipeline.annotateCoref(annotation);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Ensures that there is actually something to process for the current section. Note, this will not halt computation: it will only produce a warning message.
   */
//  private void validateSectionBuffer(List<CoreMap> sectionBuffer) {
//    // first cat all CoreMap objects in sectionBuffer into one
//    if (sectionBuffer == null || sectionBuffer.size() == 0) {
//      logger.debug("no sentences found on this invocation");
//    }
//    if (debug) {
//      logger.debug("CALL TO PROCESS");
//      logger.debug("\tsectionBuffer.size = " + sectionBuffer.size());
//    }
//  }

  /**
   * Convert tokenized sentences (<code>sentAnno</code>) into a document Annotation.<br/>
   *
   */
  public void sentencesToSection(CoreMap sectAnno, Annotation document) {
    if (sectAnno == null) {
      logger.warn("Encountered null annotated section. Skipping.");
      return;
    }

    List<CoreMap> docSents = document.get(SentencesAnnotation.class);
    List<CoreLabel> docTokens = document.get(TokensAnnotation.class);
    logger.debug("converting list of CoreMap sentences to Annotations, starting at token offset " + globalTokenOffset);

    List<CoreMap> sentAnnos = sectAnno.get(SentencesAnnotation.class);
    int maxCharEnding = -1;
    for (CoreMap sentAnno : sentAnnos) {
      List<CoreLabel> sentTokens = sentAnno.get(TokensAnnotation.class);
      int tokenEnd = globalTokenOffset + sentTokens.size();
      sentAnno.set(TokenBeginAnnotation.class, globalTokenOffset);
      sentAnno.set(TokenEndAnnotation.class, tokenEnd);
      sentAnno.set(SentenceIndexAnnotation.class, sentenceCount++);
      logger.debug("SENTENCEINDEXANNO = " + sentAnno.get(SentenceIndexAnnotation.class));
      globalTokenOffset = tokenEnd;

      for (CoreLabel token : sentTokens) {
        // note that character offsets are global
        String tokenText = token.get(TextAnnotation.class);
        token.set(CharacterOffsetBeginAnnotation.class, charOffset);
        charOffset += tokenText.length();
        token.set(CharacterOffsetEndAnnotation.class, charOffset);
        charOffset++; // Skip space
      }
      sentAnno.set(TokensAnnotation.class, sentTokens);

      sentAnno.set(CharacterOffsetBeginAnnotation.class, sentTokens.get(0).get(CharacterOffsetBeginAnnotation.class));
      sentAnno.set(CharacterOffsetEndAnnotation.class, sentTokens.get(sentTokens.size() - 1).get(CharacterOffsetEndAnnotation.class));

      logger.debug("docTokens.size before = " + docTokens.size());
      docTokens.addAll(sentTokens);
      logger.debug("\t after = " + docTokens.size());
      document.set(TokensAnnotation.class, docTokens);
      logger.debug("\t retrieved = " + document.get(TokensAnnotation.class).size());
      docSents.add(sentAnno);
      if (sentAnno.get(CharacterOffsetEndAnnotation.class) > maxCharEnding)
        maxCharEnding = sentAnno.get(CharacterOffsetEndAnnotation.class);
    }
    document.set(SentencesAnnotation.class, docSents);
    Integer oldDocCharE = document.get(CharacterOffsetEndAnnotation.class);
    if (oldDocCharE != null && maxCharEnding < oldDocCharE)
      throw new RuntimeException("The max char ending for this section (" + maxCharEnding + ") is less than the current document char ending ( " + oldDocCharE
          + ")");
    document.set(CharacterOffsetEndAnnotation.class, maxCharEnding);

  }

  public void transferAnnotations(Annotation section, Annotation document) {
    List<CoreMap> sectionSents = section.get(SentencesAnnotation.class);
    ArrayList<CoreMap> documentSents = (ArrayList<CoreMap>) document.get(SentencesAnnotation.class);
    logger.debug("\t******Document sents*********");
    for (CoreMap sectSent : sectionSents) {
      int idx = sectSent.get(SentenceIndexAnnotation.class) - 1;
      logger.debug("My index is " + idx + " (" + sectSent.get(SentenceIndexAnnotation.class) + "), and can access up to " + documentSents.size()
          + " sentences globally");
      CoreMap dSent = documentSents.get(idx);
      dSent.set(TreeAnnotation.class, sectSent.get(TreeAnnotation.class));
      logger.debug(dSent.get(TreeAnnotation.class).toString());
      logger.debug(dSent.get(TreeAnnotation.class).getLeaves().toString());
      logger.debug(sectSent.get(TokensAnnotation.class).toString());
      logger.debug(idx + " --> " + dSent.get(TokensAnnotation.class));
    }
    // tree = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
  }

  /**
   * Given a particular section {@link Section} from a {@link Communication}, further locally process
   * {@link Annotation}; add those new annotations to an
   * aggregating {@link Annotation} to use for later global processing.
   */
  public void processSection(Section section, Annotation sentenceSplitText, Annotation docAnnotation, List<Tokenization> tokenizations) {
    sentencesToSection(sentenceSplitText, docAnnotation);
    logger.debug("after sentencesToSection, before annotating");
    for (CoreMap cm : sentenceSplitText.get(SentencesAnnotation.class)) {
      logger.debug(cm.get(SentenceIndexAnnotation.class).toString());
    }
    AgigaDocument agigaDoc = annotate(sentenceSplitText);
    logger.debug("after annotating");
    for (CoreMap cm : sentenceSplitText.get(SentencesAnnotation.class)) {
      logger.debug(cm.get(SentenceIndexAnnotation.class).toString());
    }

    transferAnnotations(sentenceSplitText, docAnnotation);
    AgigaConcreteAnnotator agigaToConcrete = new AgigaConcreteAnnotator();
    agigaToConcrete.convertSection(section, agigaDoc, tokenizations);
  }

  public void processCoref(Communication comm, Annotation docAnnotation, List<Tokenization> tokenizations) {
    AgigaDocument agigaDoc = annotateCoref(docAnnotation);
    AgigaConcreteAnnotator agigaToConcrete = new AgigaConcreteAnnotator();
    SimpleEntry<EntityMentionSet, EntitySet> tuple = agigaToConcrete.convertCoref(comm, agigaDoc, tokenizations);
    comm.addToEntityMentionSets(tuple.getKey());
    comm.addToEntitySets(tuple.getValue());
  }

  /**
   * convert a tree t to its token representation
   *
   * @param t
   * @return
   * @throws IOException
   */
  protected String getText(Tree t) throws IOException {
    StringBuffer sb = new StringBuffer();
    if (t == null) {
      return null;
    }
    for (Tree tt : t.getLeaves()) {
      sb.append(tt.value());
      sb.append(" ");
    }
    return sb.toString().trim();
  }

  /*
   * This method contains code for transforming lists of Stanford's CoreLabels into Concrete tokenizations. We might want to use it if we get rid of agiga.
   */
  // private Tokenization coreLabelsToTokenization(List<CoreLabel> coreLabels) {
  // List<Token> tokens = new ArrayList<Token>();
  // List<TaggedToken> lemmas = new ArrayList<TaggedToken>();
  // List<TaggedToken> nerTags = new ArrayList<TaggedToken>();
  // List<TaggedToken> posTags = new ArrayList<TaggedToken>();

  // int tokenId = 0;
  // for (CoreLabel coreLabel : coreLabels) {
  // if (coreLabel.lemma() != null) {
  // lemmas.add(
  // TaggedToken.newBuilder()
  // .setTag(coreLabel.lemma())
  // .setTokenId(tokenId)
  // .build()
  // );
  // }

  // if (coreLabel.ner() != null) {
  // nerTags.add(
  // TaggedToken.newBuilder()
  // .setTag(coreLabel.ner())
  // .setTokenId(tokenId)
  // .build()
  // );
  // }

  // if (coreLabel.tag() != null) {
  // posTags.add(
  // TaggedToken.newBuilder()
  // .setTag(coreLabel.tag())
  // .setTokenId(tokenId)
  // .build()
  // );
  // }

  // tokens.add(
  // Token.newBuilder()
  // .setTokenId(tokenId)
  // .setTextSpan(
  // TextSpan.newBuilder()
  // .setStart(coreLabel.beginPosition())
  // .setEnd(coreLabel.endPosition())
  // .build()
  // )
  // .setText(coreLabel.value())
  // .build()
  // );

  // tokenId++;
  // }

  // Tokenization tokenization = Tokenization.newBuilder()
  // .setUuid(IdUtil.generateUUID())
  // .setKind(Kind.TOKEN_LIST)
  // .addPosTags(
  // TokenTagging.newBuilder()
  // .setUuid(IdUtil.generateUUID())
  // .addAllTaggedToken(posTags)
  // .build()
  // )
  // .addNerTags(
  // TokenTagging.newBuilder()
  // .setUuid(IdUtil.generateUUID())
  // .addAllTaggedToken(nerTags)
  // .build()
  // )
  // .addLemmas(
  // TokenTagging.newBuilder()
  // .setUuid(IdUtil.generateUUID())
  // .addAllTaggedToken(lemmas)
  // .build()
  // )
  // .addAllToken(tokens)
  // .build();

  // return tokenization;
  // }
}
