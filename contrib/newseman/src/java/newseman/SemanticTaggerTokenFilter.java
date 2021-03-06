package newseman;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import newseman.SemanticTaggerTokenFilter.TokenState;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeReflector;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.AttributeSource.State;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

/**
 * The SemanticTagger receives previously tokenized {@link TokenStream} and
 * enriches tokens with semantic categories (so called semes). This enrichment
 * is controlled/governed by the dictionary/taxonomy/ontology.
 * 
 * So for example you have this sentence:
 * 	<pre>
 * 		The proton colliders contain vacuum foo pumps.
 * </pre>
 * 
 * It was previously tokenized by the chain of filters:
 * 
 * <pre>
 *  [the] 
 *  [proton] 
 *  [colliders] 
 *  [contain] 
 *  [vacuum] 
 *  [foo]
 *  [pumps]
 * </pre>
 * 
 * Then after we process it (given we have a proper dictionary), the results may
 * be:
 * 
 * <pre>
 * tokens                      explanation
 * -----------------------------------------------------------------------------
 *  [the] 
 *  [proton]                   original token, offset=[5:11]
 *  	[proton collider]      multi-token group: position increment=0, type=TOKEN_TYPE_SYN, offset=[5:11]
 *  	[PP collider]          canonical form: position increment=0, type=TOKEN_TYPE_SYN, offset=[5:11]
 *  	[abc]                  semantic code: position increment=0, type=TOKEN_TYPE_SEM, offset=[5:11]
 *  	[bce]								
 *  	[dce] 								
 *  [collider]                 position increment=1
 *  [contain] 
 *  [vacuum]                   original token, offset=[29:35]
 *  	[vacuum pumps]         SEMAN found 'vacuum pumps' despite the interjected 'foo', multi-token group: position increment=0, type=TOKEN_TYPE_SYN, offset=[29:49]
 *  	[vacuum pump]          multi-token group: position increment=0, type=TOKEN_TYPE_CANONICAL, offset=[29:49]
 *  	[abe]                  semantic code: position increment=0, type=TOKEN_TYPE_SEM, offset=[29:49]
 *  	[bcf]                    (note how the offset is set to cover the range vacuum-foo-pumps)
 *  [foo]                      position increment=1, offset=[39:42]
 *  [pumps]	                   position increment=1, offset=[43:49]
 * 
 * </pre>
 * The translation is done by a Pythonic library called SEMAN. Please see 
 * documentation of SEMAN for further details.
 * 
 * 
 * 
 */
public class SemanticTaggerTokenFilter extends TokenFilter {

	/** The Token.type used to indicate semes to higher level filters. */
	public static final String TOKEN_TYPE_SEM = "SEM";
	public static final String TOKEN_TYPE_SYNONYM = "SYN";
	public static final String TOKEN_TYPE_CANONICAL = "CAN";

	protected SemanticTagger semanTagger = null; // Seman wrapper
	protected int maxBuffer = 4096; // # of tokens we send to Seman in one go
	protected String valueSeparator = " ";
	private List<TokenState> enhancedTokens = null;
	private Iterator<TokenState> etIterator = null;

	
	private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
	private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
	private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
	private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);
	private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
	private final PayloadAttribute payloadAtt = addAttribute(PayloadAttribute.class);
	private final SemanticTagAttribute semAtt = addAttribute(SemanticTagAttribute.class);

	
	private ArrayList<Object> stack = new ArrayList<Object>();
	private int index = 0;
	private AttributeSource.State current = null;
	private int todo = 0;
	
	private AttributeSource wrapper = null;
	
	/**
	 * Creates an instance for the given underlying stream and synonym table.
	 * 
	 * @param input
	 *            the underlying child token stream
	 * @param seman
	 *            instance of {@link SemanticTagger} which will be called to
	 *            translate the input {@link TokenStream}
	 */
	public SemanticTaggerTokenFilter(TokenStream input, SemanticTagger seman) {

		super(input);
		if (input == null)
			throw new IllegalArgumentException("input must not be null");
		if (seman == null)
			throw new IllegalArgumentException("seman must not be null");
		this.semanTagger = seman;
		
		if (wrapper == null) {
			wrapper = new AttributeSource();
			//wrapper = input.cloneAttributes();
			wrapper.addAttribute(CharTermAttribute.class);
			wrapper.addAttribute(FlagsAttribute.class);
			wrapper.addAttribute(OffsetAttribute.class);
			wrapper.addAttribute(TypeAttribute.class);
			wrapper.addAttribute(PositionIncrementAttribute.class);
			wrapper.addAttribute(SemanticTagAttribute.class);
			wrapper.addAttribute(PayloadAttribute.class);
		}
		
		valueSeparator = Pattern.quote(semanTagger.getSeparator());
	}
	

	/** Returns the next token in the stream, or null at EOS. */
	@Override
	public final boolean incrementToken() throws IOException {
		
		while (todo > 0) { // pop items from stack
			Object curVal = stack.get(index++);
			if (curVal instanceof String) {
				if (createToken(current, (String) curVal, (String) stack.get(index++), (Integer) stack.get(index++))) {
					todo = todo - 3;
					return true;
				}
			}
			else {
				clearAttributes();
				restoreState((State) curVal);
				current = captureState();
				todo--;
				return true;
			}
		}
		

		if (etIterator==null || !etIterator.hasNext()) {
			if (!input.incrementToken()) {
				return false; // EOS; iterator exhausted
			}
			enhancedTokens = advanceInputTokenStream(); // advance the input token stream
			if (enhancedTokens==null) {
				return false;
			}
			etIterator = enhancedTokens.iterator();
		}
		
		
		if (etIterator.hasNext()) {
			TokenState ct = etIterator.next();
			restoreState(ct.getState());
			
			stack.clear();
			
			if (ct.hasOtherNames()) {
				for (String name: ct.getOtherNames()) {
					stack.add(name);
					stack.add(TOKEN_TYPE_CANONICAL);
					stack.add(0);
				}
			
			}
			
			if (ct.hasSynonyms()) {
				for (String name: ct.getSynonyms()) {
					stack.add(name);
					stack.add(TOKEN_TYPE_SYNONYM);
					stack.add(0);
				}
			
			}
			
			if (ct.hasSemes()) {
				for (String sem: ct.getSemes()) {
					stack.add(sem);
					stack.add(TOKEN_TYPE_SEM);
					stack.add(0);
				}
			}
			
			if (ct.hasOtherSemes()) {
				for (String sem: ct.getOtherSemes()) {
					stack.add(sem);
					stack.add(TOKEN_TYPE_SEM);
					stack.add(0);
				}
			}
			
			if (ct.hasTokenElements()) {
				// special treatment - multi-tokens, we want the multi-t to be after the first member
				TokenState[] elements = ct.getTokenElements();
				restoreState(elements[0].getState());
				stack.add(0, ct.getState());
				for (int e=1;e<elements.length;e++) {
					stack.add(elements[e].getState());
				}
			}
			
			
			todo = stack.size();
			index = 0;
			if (todo > 0) {
				current = captureState();
			}
		}
		
		
		return true;
	}
	
	
	public String[][] getTranslations(String[][] tokens) throws IOException {
		return semanTagger.translateTokens(tokens);
	}

	
	/**
	 * Gets the buffer of tokens (enhanced/translated) from SEMAN
	 * Each {@link TokenState} represents one token, with added information
	 * such as semes, synonyms, other tokens (this token was made of)
	 * 
	 * @return List<TokenState> collection of token ids, and token
	 * 		states
	 * @throws IOException
	 */
	private List<TokenState> advanceInputTokenStream() throws IOException {
		
		Map<String, Integer> id2state= new HashMap<String, Integer>(maxBuffer);
		List<TokenState> tokens = new ArrayList<TokenState>(maxBuffer);
		String[][] passedTokens = new String[maxBuffer][];

		
		// first collect the tokens (we are passing in an array)
		passedTokens[0] = new String[]{"token", "id"}; // header
		int i = 0;
	    do {
	    	TokenState s = new TokenState(input.captureState());
	    	String tid = Integer.toString(input.hashCode());
	    	id2state.put(tid, i);
	    	i++;
	    	tokens.add(s);
	    	String[] token = {termAtt.toString(), tid};
	    	passedTokens[i] = token;
	    } while (i < maxBuffer && input.incrementToken());
	    
	    
	    // correct the size, remove the null tokens
	    if (i < maxBuffer) {
	    	String[][] t = new String[i+1][];
	    	for (int j=0;j<i+1;j++) {
	    		t[j] = passedTokens[j];
	    	}
	    	passedTokens = t;
	    }
	    
	    
	    String[][] translated;
		// translate tokens
	    if (passedTokens.length > 0) {
	    	translated = getTranslations(passedTokens);
	    }
	    else {
	    	return null;
	    }
	    
	    
	    // get the col ids of the data we seek
	    String[] header = translated[0];
		int sem_idx = 0;
		int multi_sem_idx = 0;
		int synonyms_idx = 0;
		int multi_synonyms_idx = 0;
		int pos_idx = 0;
		int multi_idx = 0;
	    for (int j=2;j<header.length;j++) {
	    	String h = header[j];
	    	if (h.equals("sem")) { // semantic code of this token (or multi-token if we used rewrite)
	    		sem_idx = j;
	    	}
	    	else if(h.equals("extra-sem")) { // semantic code of multigroup (if we used "add" operation)
	    		multi_sem_idx = j;
	    	}
	    	else if(h.equals("extra-canonical")) { // canonical form of the multi-token
	    		synonyms_idx = j;
	    	}
	    	else if(h.equals("extra-synonyms")) { // synonyms for the multi-sem group
	    		multi_synonyms_idx = j;
	    	}
	    	else if(h.equals("POS")) { // part-of-speech tag
	    		pos_idx = j;
	    	}
	    	else if(h.equals("multi")) { // indicator which says the token was created by extra-callback
	    		multi_idx = j;
	    	}
	    }
	    
	    
	    // enhance tokens, build return structure, results may also be reordered or 
	    // contain completely new tokens (and some tokens might be missing)
	    List<TokenState> results = new ArrayList<TokenState>(translated.length - 1);
	    String[] r = null;
	    TokenState token = null;
	    String id = null;
	    String[] semes;
	    for (int k=1;k<translated.length;k++) {
	    	r = translated[k];
	    	id = r[1];
	    	if (id2state.containsKey(id)) {
    			token = tokens.get(id2state.get(id));
    		}
    		else {
    			if (id.contains(semanTagger.getSeparator())) {
    				String ids[] = id.split(valueSeparator); // we deal with multi-token
    				TokenState[] elements = new TokenState[ids.length];
    				for (int t=0;t<ids.length;t++) {
    					elements[t] = tokens.get(id2state.get(ids[t]));
    				}
    				token = createNewTokenState(r, elements);
    				// token was post-merged, we have the elements after it
    				// this feels hacking: the best would be to send offsets to SEMAN
    				// and modify the semans using some callback (but that seems slower)
    				if (r.length>multi_idx && r[multi_idx]!= null) {
    					token.setTokenElements(new TokenState[]{elements[0]});
    				}
    			}
    			else {
    				token = createNewTokenState(r, token, null);
    			}
    		}
	    	
	    	for (int c=2;c<r.length;c++) {
	    		if (c == sem_idx && r[sem_idx] != null) {
		    		token.setSemes(r[sem_idx].split(valueSeparator));
	    		}
	    		else if (c == synonyms_idx && r[synonyms_idx] != null) {
		    		token.setSynonyms(r[synonyms_idx].split(valueSeparator));
		    	}
	    		else if (c == multi_sem_idx && r[multi_sem_idx] != null) {
		    		token.setOtherSemes(r[multi_sem_idx].split(valueSeparator));
		    	}
	    		else if (c == multi_synonyms_idx && r[multi_synonyms_idx] != null) {
		    		token.setOtherNames(r[multi_synonyms_idx].split(valueSeparator));
		    	}
	    		// TODO: add POS attribute?
		    	/*
		    	if (r.length > pos_idx && r[pos_idx] != null) {
		    		token.setSynonyms(r[pos_idx].split(valueSeparator));
		    	}
		    	*/
	    		// HERE can be generic key/value implementation, but do we want?
	    	}
	    	
	    	results.add(token);
	    }
	    
	    return results;
	}
	
	
	private TokenState createNewTokenState(String[] data, TokenState[] elements) {
		
		TokenState ts = createNewTokenState(data, elements[0], elements[elements.length-1]);
		ts.setTokenElements(elements);
		return ts;
	}
	
	/**
	 * 
	 * Creates a new token which is based on the previous token (or if that is empty, it 
	 * has offset 0)
	 * 
	 * TODO: should we change the underlying offset parameters? Probably not, as the input
	 * source is a different thing. 
	 * 
	 * @param data
	 * @param previousToken
	 * @return
	 */
	private TokenState createNewTokenState(String[] data, 
			TokenState previousToken, TokenState lastToken) {
		
		String token = data[0];
		
		
		
		int incr = 0; 
		int startOffset = 0;
		int endOffset = 0;
		
		if (lastToken != null) {
			wrapper.clearAttributes();
			wrapper.restoreState(lastToken.getState());
			//incr = wrapper.getAttribute(PositionIncrementAttribute.class).getPositionIncrement();
			endOffset = wrapper.getAttribute(OffsetAttribute.class).endOffset();
		}
		
		if (previousToken != null) {
			wrapper.clearAttributes();
			wrapper.restoreState(previousToken.getState());
			startOffset = wrapper.getAttribute(OffsetAttribute.class).startOffset();
			//incr = incr == 0 ? wrapper.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() : incr;
			endOffset = endOffset == 0 ? wrapper.getAttribute(OffsetAttribute.class).endOffset() : endOffset;
		}

		
		wrapper.getAttribute(CharTermAttribute.class).setEmpty().append(token);
		wrapper.getAttribute(OffsetAttribute.class).setOffset(startOffset, endOffset);
		wrapper.getAttribute(PositionIncrementAttribute.class).setPositionIncrement(incr);
		
		return new TokenState(wrapper.captureState());
	}
	
	/**
	 * Creates and returns a token for the given synonym of the current input
	 * token; Override for custom (stateless or stateful) behavior, if desired.
	 * 
	 * @param current
	 *            the current token from the underlying child stream
	 * @return a new token, or null to indicate that the given synonym should be
	 *         ignored
	 */
	protected boolean createToken(AttributeSource.State current, 
			String value, String type, int incr) {
		//clearAttributes();
		restoreState(current);
		termAtt.setEmpty().append(value);
		typeAtt.setType(type);
		posIncrAtt.setPositionIncrement(incr);
		return true;
	}


	@Override
	public void reset() throws IOException {
		super.reset();
		stack.clear();
		index = 0;
		current = null;
		todo = 0;
		enhancedTokens = null;
		etIterator = null;
	}
	
	class TokenState {
		State state = null;
		String[] semes = null;
		String[] otherNames = null;
		String[] otherSemes = null;
		String[] synonyms = null;
		TokenState[] tokenElements = null;
		
		TokenState(State state) {
			this.state = state;
		}
		public boolean hasTokenElements() {
			return tokenElements != null;
		}
		public void setTokenElements(TokenState[] elements) {
			tokenElements = elements;
			
		}
		public TokenState[] getTokenElements() {
			return tokenElements;
			
		}
		public State getState() {
			return this.state;
		}
		public void setState(State state) {
			this.state = state;
		}
		public boolean hasSemes() {
			return semes != null;
		}
		public String[] getSemes() {
			return this.semes;
		}
		public void setSemes(String[] semes) {
			this.semes = semes;
		}
		public boolean hasOtherNames() {
			return otherNames != null;
		}
		public String[] getOtherNames() {
			return this.otherNames;
		}
		public void setOtherNames(String[] name) {
			this.otherNames = name;
		}
		public boolean hasOtherSemes() {
			return otherSemes != null;
		}
		public String[] getOtherSemes() {
			return this.otherSemes;
		}
		public void setOtherSemes(String[] semes) {
			this.otherSemes = semes;
		}
		public boolean hasSynonyms() {
			return synonyms != null;
		}
		public String[] getSynonyms() {
			return this.synonyms;
		}
		public void setSynonyms(String[] synonyms) {
			this.synonyms = synonyms;
		}
	}
	
}
