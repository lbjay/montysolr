package org.apache.solr.analysis;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.analysis.util.TokenFilterFactory;

public class DiagnoseFilterFactory extends TokenFilterFactory {

    public void init(Map<String, String> args) {
	    super.init(args);
	}
	  
	/* (non-Javadoc)
	 * @see org.apache.solr.analysis.TokenFilterFactory#create(org.apache.lucene.analysis.TokenStream)
	 */
	public DiagnoseFilter create(TokenStream input) {
		return new DiagnoseFilter(input);
	}

}

final class DiagnoseFilter extends TokenFilter {

  private int numTokens = 0;
  
  private final PositionIncrementAttribute posIncrAtt;
  private final TypeAttribute typeAtt;
  private final CharTermAttribute termAtt;
  private final OffsetAttribute offsetAtt;

  
  public DiagnoseFilter(TokenStream input) {
    super(input);
    posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    typeAtt = addAttribute(TypeAttribute.class);
    termAtt = addAttribute(CharTermAttribute.class);
    offsetAtt = addAttribute(OffsetAttribute.class);
  }

  
  

  /* (non-Javadoc)
   * @see org.apache.lucene.analysis.TokenStream#incrementToken()
   */
  @Override
  public boolean incrementToken() throws IOException {

    if (!input.incrementToken()) return false;
    
    System.out.println("term=" + termAtt.toString() + " pos=" + posIncrAtt.getPositionIncrement() + " type=" + typeAtt.type() + " offsetStart=" + offsetAtt.startOffset() + " offsetEnd=" + offsetAtt.endOffset());
    
    return true;
  }


  @Override
  public void reset() throws IOException {
    super.reset();
    numTokens = 0;
  }
}