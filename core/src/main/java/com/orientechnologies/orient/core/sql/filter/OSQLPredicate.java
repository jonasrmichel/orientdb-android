/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.sql.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.orientechnologies.common.parser.OBaseParser;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.command.OCommandPredicate;
import com.orientechnologies.orient.core.exception.OQueryParsingException;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordSchemaAware;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.sql.OCommandExecutorSQLSelect;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.OSQLEngine;
import com.orientechnologies.orient.core.sql.OSQLHelper;
import com.orientechnologies.orient.core.sql.operator.OQueryOperator;
import com.orientechnologies.orient.core.sql.operator.OQueryOperatorNot;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;

/**
 * Parses text in SQL format and build a tree of conditions.
 * 
 * @author Luca Garulli
 * 
 */
public class OSQLPredicate extends OBaseParser implements OCommandPredicate {
  protected Set<OProperty>                properties = new HashSet<OProperty>();
  protected OSQLFilterCondition           rootCondition;
  protected List<String>                  recordTransformed;
  protected List<OSQLFilterItemParameter> parameterItems;
  protected int                           braces;
  protected OCommandContext               context;

  public OSQLPredicate() {
  }

  public OSQLPredicate(final String iText) {
    text(iText);
  }

  protected void throwSyntaxErrorException(final String iText) {
    throw new OCommandSQLParsingException(iText + ". Use " + getSyntax(), text, parserGetPreviousPosition());
  }

  public OSQLPredicate text(final String iText) {
    try {
      text = iText;
      textUpperCase = text.toUpperCase(Locale.ENGLISH);
      parserSetCurrentPosition(0);
      parserSkipWhiteSpaces();

      rootCondition = (OSQLFilterCondition) extractConditions(null);
    } catch (OQueryParsingException e) {
      if (e.getText() == null)
        // QUERY EXCEPTION BUT WITHOUT TEXT: NEST IT
        throw new OQueryParsingException("Error on parsing query", text, parserGetCurrentPosition(), e);

      throw e;
    } catch (Throwable t) {
      throw new OQueryParsingException("Error on parsing query", text, parserGetCurrentPosition(), t);
    }
    return this;
  }

  public boolean evaluate(final ORecord<?> iRecord, final OCommandContext iContext) {
    if (rootCondition == null)
      return true;

    return (Boolean) rootCondition.evaluate((ORecordSchemaAware<?>) iRecord, iContext);
  }

  private Object extractConditions(final OSQLFilterCondition iParentCondition) {
    final int oldPosition = parserGetCurrentPosition();
    final String[] words = nextValue(true);

    if (words != null && words.length > 0 && (words[0].equalsIgnoreCase("SELECT") || words[0].equalsIgnoreCase("TRAVERSE"))) {
      // SUB QUERY
      final StringBuilder embedded = new StringBuilder();
      OStringSerializerHelper.getEmbedded(text, oldPosition - 1, -1, embedded);
      parserMoveCurrentPosition(embedded.length() + 1);
      return new OSQLSynchQuery<Object>(embedded.toString());
    }

    parserSetCurrentPosition(oldPosition);
    final OSQLFilterCondition currentCondition = extractCondition();

    // CHECK IF THERE IS ANOTHER CONDITION ON RIGHT
    if (!parserSkipWhiteSpaces())
      // END OF TEXT
      return currentCondition;

    if (!parserIsEnded() && parserGetCurrentChar() == ')')
      return currentCondition;

    final OQueryOperator nextOperator = extractConditionOperator();
    if (nextOperator == null)
      return currentCondition;

    if (nextOperator.precedence > currentCondition.getOperator().precedence) {
      // SWAP ITEMS
      final OSQLFilterCondition subCondition = new OSQLFilterCondition(currentCondition.right, nextOperator);
      currentCondition.right = subCondition;
      subCondition.right = extractConditions(subCondition);
      return currentCondition;
    } else {
      final OSQLFilterCondition parentCondition = new OSQLFilterCondition(currentCondition, nextOperator);
      parentCondition.right = extractConditions(parentCondition);
      return parentCondition;
    }
  }

  protected OSQLFilterCondition extractCondition() {
    if (!parserSkipWhiteSpaces())
      // END OF TEXT
      return null;

    // EXTRACT ITEMS
    Object left = extractConditionItem(true, 1);

    if (checkForEnd(left.toString()))
      return null;

    final OQueryOperator oper;
    final Object right;

    if (left instanceof OQueryOperator && ((OQueryOperator) left).isUnary()) {
      oper = (OQueryOperator) left;
      left = extractConditionItem(false, 1);
      right = null;
    } else {
      oper = extractConditionOperator();
      right = oper != null ? extractConditionItem(false, oper.expectedRightWords) : null;
    }

    // CREATE THE CONDITION OBJECT
    return new OSQLFilterCondition(left, oper, right);
  }

  protected boolean checkForEnd(final String iWord) {
    if (iWord != null
        && (iWord.equals(OCommandExecutorSQLSelect.KEYWORD_ORDER) || iWord.equals(OCommandExecutorSQLSelect.KEYWORD_LIMIT) || iWord
            .equals(OCommandExecutorSQLSelect.KEYWORD_SKIP))) {
      parserMoveCurrentPosition(iWord.length() * -1);
      return true;
    }
    return false;
  }

  private OQueryOperator extractConditionOperator() {
    if (!parserSkipWhiteSpaces())
      // END OF PARSING: JUST RETURN
      return null;

    if (parserGetCurrentChar() == ')')
      // FOUND ')': JUST RETURN
      return null;

    parserNextWord(true, " 0123456789'\"");
    final String word = parserGetLastWord();

    if (checkForEnd(word))
      return null;

    for (OQueryOperator op : OSQLEngine.getInstance().getRecordOperators()) {
      if (word.startsWith(op.keyword)) {
        final List<String> params = new ArrayList<String>();
        // CHECK FOR PARAMETERS
        if (word.length() > op.keyword.length() && word.charAt(op.keyword.length()) == OStringSerializerHelper.EMBEDDED_BEGIN) {
          int paramBeginPos = parserGetCurrentPosition() - (word.length() - op.keyword.length());
          parserSetCurrentPosition(OStringSerializerHelper.getParameters(text, paramBeginPos, -1, params));
        } else if (!word.equals(op.keyword))
          throw new OQueryParsingException("Malformed usage of operator '" + op.toString() + "'. Parsed operator is: " + word);

        try {
          return op.configure(params);
        } catch (Exception e) {
          throw new OQueryParsingException("Syntax error using the operator '" + op.toString() + "'. Syntax is: " + op.getSyntax());
        }
      }
    }

    throw new OQueryParsingException("Unknown operator " + word, text, parserGetCurrentPosition());
  }

  private Object extractConditionItem(final boolean iAllowOperator, final int iExpectedWords) {
    final Object[] result = new Object[iExpectedWords];

    for (int i = 0; i < iExpectedWords; ++i) {
      final String[] words = nextValue(true);
      if (words == null)
        break;

      final int lastPosition = parserIsEnded() ? text.length() : parserGetCurrentPosition();

      if (words[0].length() > 0 && words[0].charAt(0) == OStringSerializerHelper.EMBEDDED_BEGIN) {
        braces++;

        // SUB-CONDITION
        parserSetCurrentPosition(lastPosition - words[0].length() + 1);

        final Object subCondition = extractConditions(null);

        if (!parserSkipWhiteSpaces() || parserGetCurrentChar() == ')')
          braces--;

        parserMoveCurrentPosition(+1);

        result[i] = subCondition;
      } else if (words[0].charAt(0) == OStringSerializerHelper.COLLECTION_BEGIN) {
        // COLLECTION OF ELEMENTS
        parserSetCurrentPosition(lastPosition - words[0].length());

        final List<String> stringItems = new ArrayList<String>();
        parserSetCurrentPosition(OStringSerializerHelper.getCollection(text, parserGetCurrentPosition(), stringItems));

        if (stringItems.get(0).charAt(0) == OStringSerializerHelper.COLLECTION_BEGIN) {

          final List<List<Object>> coll = new ArrayList<List<Object>>();
          for (String stringItem : stringItems) {
            final List<String> stringSubItems = new ArrayList<String>();
            OStringSerializerHelper.getCollection(stringItem, 0, stringSubItems);

            coll.add(convertCollectionItems(stringSubItems));
          }

          result[i] = coll;

        } else {
          result[i] = convertCollectionItems(stringItems);
        }

        parserMoveCurrentPosition(+1);

      } else if (words[0].startsWith(OSQLFilterItemFieldAll.NAME + OStringSerializerHelper.EMBEDDED_BEGIN)) {

        result[i] = new OSQLFilterItemFieldAll(this, words[1]);

      } else if (words[0].startsWith(OSQLFilterItemFieldAny.NAME + OStringSerializerHelper.EMBEDDED_BEGIN)) {

        result[i] = new OSQLFilterItemFieldAny(this, words[1]);

      } else {

        if (words[0].equals("NOT")) {
          if (iAllowOperator)
            return new OQueryOperatorNot();
          else {
            // GET THE NEXT VALUE
            final String[] nextWord = nextValue(true);
            if (nextWord != null && nextWord.length == 2) {
              words[1] = words[1] + " " + nextWord[1];

              if (words[1].endsWith(")"))
                words[1] = words[1].substring(0, words[1].length() - 1);
            }
          }
        }

        if (words[1].endsWith(")")) {
          final int openParenthesis = words[1].indexOf('(');
          if (openParenthesis == -1) {
            words[1] = words[1].substring(0, words[1].length() - 1);
            parserMoveCurrentPosition(-1);
          }
        }

        result[i] = OSQLHelper.parseValue(this, this, words[1], context);
      }
    }

    return iExpectedWords == 1 ? result[0] : result;
  }

  private List<Object> convertCollectionItems(List<String> stringItems) {
    List<Object> coll = new ArrayList<Object>();
    for (String s : stringItems) {
      coll.add(OSQLHelper.parseValue(this, this, s, context));
    }
    return coll;
  }

  public OSQLFilterCondition getRootCondition() {
    return rootCondition;
  }

  private String[] nextValue(final boolean iAdvanceWhenNotFound) {
    if (!parserSkipWhiteSpaces())
      return null;

    int begin = parserGetCurrentPosition();
    char c;
    char stringBeginCharacter = ' ';
    int openBraces = 0;
    int openBraket = 0;
    boolean escaped = false;
    boolean escapingOn = false;

    while (!parserIsEnded()) {
      c = parserGetCurrentChar();

      if (stringBeginCharacter == ' ' && (c == '"' || c == '\'')) {
        // QUOTED STRING: GET UNTIL THE END OF QUOTING
        stringBeginCharacter = c;
      } else if (stringBeginCharacter != ' ') {
        // INSIDE TEXT
        if (c == '\\') {
          escapingOn = true;
          escaped = true;
        } else {
          if (c == stringBeginCharacter && !escapingOn) {
            stringBeginCharacter = ' ';

            if (openBraket == 0 && openBraces == 0) {
              if (iAdvanceWhenNotFound)
                parserMoveCurrentPosition(+1);
              break;
            }
          }

          if (escapingOn)
            escapingOn = false;
        }
      } else if (c == '#' && parserGetCurrentPosition() == begin) {
        // BEGIN OF RID
      } else if (c == '(') {
        openBraces++;
      } else if (c == ')' && openBraces > 0) {
        openBraces--;
      } else if (c == OStringSerializerHelper.COLLECTION_BEGIN) {
        openBraket++;
      } else if (c == OStringSerializerHelper.COLLECTION_END) {
        openBraket--;
        if (openBraket == 0 && openBraces == 0) {
          // currentPos++;
          // break;
        }
      } else if (c == ' ' && openBraces == 0 && openBraket == 0) {
        break;
      } else if (!Character.isLetter(c) && !Character.isDigit(c) && c != '.' && c != '$' && c != ':' && c != '-' && c != '_'
          && c != '+' && c != '@' && openBraces == 0 && openBraket == 0) {
        if (iAdvanceWhenNotFound)
          parserMoveCurrentPosition(+1);
        break;
      }

      parserMoveCurrentPosition(+1);
    }

    int pos = parserGetCurrentPosition();
    if (pos == -1)
      pos = text.length();

    if (escaped)
      return new String[] { OStringSerializerHelper.decode(textUpperCase.substring(begin, pos)),
          OStringSerializerHelper.decode(text.substring(begin, pos)) };
    else
      return new String[] { textUpperCase.substring(begin, pos), text.substring(begin, pos) };
  }

  @Override
  public String toString() {
    if (rootCondition != null)
      return "Parsed: " + rootCondition.toString();
    return "Unparsed: " + text;
  }

  /**
   * Binds parameters.
   * 
   * @param iArgs
   */
  public void bindParameters(final Map<Object, Object> iArgs) {
    if (parameterItems == null || iArgs == null || iArgs.size() == 0)
      return;

    for (Entry<Object, Object> entry : iArgs.entrySet()) {
      if (entry.getKey() instanceof Integer)
        parameterItems.get(((Integer) entry.getKey())).setValue(entry.setValue(entry.getValue()));
      else {
        String paramName = entry.getKey().toString();
        for (OSQLFilterItemParameter value : parameterItems) {
          if (value.getName().equalsIgnoreCase(paramName)) {
            value.setValue(entry.getValue());
            break;
          }
        }
      }
    }
  }

  public OSQLFilterItemParameter addParameter(final String iName) {
    final String name;
    if (iName.charAt(0) == OStringSerializerHelper.PARAMETER_NAMED) {
      name = iName.substring(1);

      // CHECK THE PARAMETER NAME IS CORRECT
      if (!OStringSerializerHelper.isAlphanumeric(name)) {
        throw new OQueryParsingException("Parameter name '" + name + "' is invalid, only alphanumeric characters are allowed");
      }
    } else
      name = iName;

    final OSQLFilterItemParameter param = new OSQLFilterItemParameter(name);

    if (parameterItems == null)
      parameterItems = new ArrayList<OSQLFilterItemParameter>();

    parameterItems.add(param);
    return param;
  }

  public void setRootCondition(final OSQLFilterCondition iCondition) {
    rootCondition = iCondition;
  }
}
