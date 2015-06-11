/*
 * Copyright 2012-2015 Johns Hopkins University HLTCOE. All rights reserved.
 * See LICENSE in the project root directory.
 */
package edu.jhu.hlt.concrete.stanford;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.analytics.base.AnalyticException;
import edu.jhu.hlt.concrete.analytics.base.TokenizationedCommunicationAnalytic;
import edu.jhu.hlt.concrete.miscommunication.MiscommunicationException;
import edu.jhu.hlt.concrete.miscommunication.tokenized.CachedTokenizationCommunication;
import edu.jhu.hlt.concrete.miscommunication.tokenized.TokenizedCommunication;
import edu.jhu.hlt.concrete.util.Timing;

/**
 * Given tokenized Concrete as input, this class will annotate sentences with
 * the Stanford NLP tools and add the annotations back in their Concrete
 * representations.<br>
 * <br>
 * This class assumes that the input has been tokenized using a PTB-like
 * tokenization. There is a known bug in the Stanford library which will throw
 * an exception when trying to perform semantic head finding on after parsing
 * the sentence "( CROSSTALK )". The error will not occur given the input
 * "-LRB- CROSSTALK -RRB-".
 *
 * @author mgormley
 * @author npeng
 */
public class AnnotateTokenizedConcrete implements TokenizationedCommunicationAnalytic<StanfordPostNERCommunication> {

  private static final Logger logger = LoggerFactory
      .getLogger(AnnotateTokenizedConcrete.class);

//  private final static String[] chineseSectionName = new String[] { "</TURN>",
//      "</HEADLINE>", "</TEXT>", "</POST>", "</post>", "</quote>" };
//  private static final Set<String> chineseSectionNameSet = new HashSet<String>(
//      Arrays.asList(chineseSectionName));

  private final ConcreteStanfordPreCorefAnalytic analytic;

  public AnnotateTokenizedConcrete(PipelineLanguage lang) {
    this.analytic = new ConcreteStanfordPreCorefAnalytic(lang);
  }

  /* (non-Javadoc)
   * @see edu.jhu.hlt.concrete.analytics.base.Analytic#annotate(edu.jhu.hlt.concrete.Communication)
   */
  @Override
  public StanfordPostNERCommunication annotate(Communication arg0) throws AnalyticException {
    try {
      return this.annotate(new CachedTokenizationCommunication(arg0));
    } catch (MiscommunicationException e) {
      throw new AnalyticException(e);
    }
  }

  /* (non-Javadoc)
   * @see edu.jhu.hlt.concrete.safe.metadata.SafeAnnotationMetadata#getTimestamp()
   */
  @Override
  public long getTimestamp() {
    return Timing.currentLocalTime();
  }

  /* (non-Javadoc)
   * @see edu.jhu.hlt.concrete.metadata.tools.MetadataTool#getToolName()
   */
  @Override
  public String getToolName() {
    return AnnotateTokenizedConcrete.class.getSimpleName();
  }

  /* (non-Javadoc)
   * @see edu.jhu.hlt.concrete.metadata.tools.MetadataTool#getToolVersion()
   */
  @Override
  public String getToolVersion() {
    return ProjectConstants.VERSION;
  }

  /* (non-Javadoc)
   * @see edu.jhu.hlt.concrete.analytics.base.TokenizationedCommunicationAnalytic#annotate(edu.jhu.hlt.concrete.miscommunication.tokenized.TokenizedCommunication)
   */
  @Override
  public StanfordPostNERCommunication annotate(TokenizedCommunication arg0) throws AnalyticException {
    try {
      TokenizedCommunication tc = this.analytic.annotate(arg0);
      return new StanfordPostNERCommunication(tc.getRoot());
    } catch (MiscommunicationException e) {
      throw new AnalyticException(e);
    }
  }
}
