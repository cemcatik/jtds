//
// Copyright 1998 CDS Networks, Inc., Medford Oregon
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
// 3. All advertising materials mentioning features or use of this software
//    must display the following acknowledgement:
//      This product includes software developed by CDS Networks, Inc.
// 4. The name of CDS Networks, Inc.  may not be used to endorse or promote
//    products derived from this software without specific prior
//    written permission.
//
// THIS SOFTWARE IS PROVIDED BY CDS NETWORKS, INC. ``AS IS'' AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED.  IN NO EVENT SHALL CDS NETWORKS, INC. BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
// OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
// HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
// OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
// SUCH DAMAGE.
//

package net.sourceforge.jtds.jdbc;

import java.sql.*;
import java.util.HashMap;

abstract public class EscapeProcessor {
    public static final String cvsVersion = "$Id: EscapeProcessor.java,v 1.7 2004-01-29 00:36:45 bheineman Exp $";

    private static final String ESCAPE_PREFIX_DATE = "d ";
    private static final String ESCAPE_PREFIX_TIME = "t ";
    private static final String ESCAPE_PREFIX_TIMESTAMP = "ts ";
    private static final String ESCAPE_PREFIX_OUTER_JOIN = "oj ";
    private static final String ESCAPE_PREFIX_FUNCTION = "fn ";
    private static final String ESCAPE_PREFIX_CALL = "call ";
    private static final String ESCAPE_PREFIX_ESCAPE_CHAR = "escape ";

    private static final int NORMAL = 0;
    private static final int IN_STRING = 1;
    private static final int IN_ESCAPE = 2;

    // Map of JDBC function names to database function names
    private static HashMap _functionMap = new HashMap();

    static {
        _functionMap.put("user", "user_name");
        _functionMap.put("database", "db_name");
        _functionMap.put("ifnull", "isnull");
        _functionMap.put("now", "translateDate");
        _functionMap.put("atan2", "atn2");
        _functionMap.put("length", "len");
        _functionMap.put("locate", "charindex");
        _functionMap.put("repeat", "replicate");
        _functionMap.put("insert", "stuff");
        _functionMap.put("lcase", "lower");
        _functionMap.put("ucase", "upper");
    }

    private String input;

    public EscapeProcessor(String sql) {
        input = sql;
    }

    /**
     * Converts JDBC escape syntax to native SQL.
     * <p>
     * NOTE: This method is now structured the way it is for optimization purposes.
     * <p>
     * if (state==NORMAL) else if (state==IN_STRING) else
     * replaces the
     * switch (state) case NORMAL: case IN_STRING
     * as it is faster when there are few case statements.
     * <p>
     * Also, IN_ESCAPE is not checked for and a simple 'else' is used instead as it is the
     * only other state that can exist.
     * <p>
     * char ch = chars[i] is used in conjunction with input.toCharArray() to avoid the
     * getfield opcode.
     * <p>
     * If any changes are made to this method, please test the performance of the change
     * to ensure that there is no degradation.  The cost of parsing SQL for JDBC escapes
     * needs to be as close to zero as possible.
     */
    public String nativeString() throws SQLException {
        char[] chars = input.toCharArray(); // avoid getfield opcode
        StringBuffer result = new StringBuffer(chars.length);
        StringBuffer escape = null;
        int state = NORMAL;

        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i]; // avoid getfield opcode

            if (state == NORMAL) {
                if (ch == '{') {
                    state = IN_ESCAPE;

                    if (escape == null) {
                        escape = new StringBuffer();
                    } else {
                        escape.delete(0, escape.length());
                    }
                } else {
                    result.append(ch);

                    if (ch == '\'') {
                        state = IN_STRING;
                    }
                }
            } else if (state == IN_STRING) {
                result.append(ch);

                // NOTE: This works even if a single quote is being used as an escape
                // character since the next single quote found will force the state
                // to be == to IN_STRING again.
                if (ch == '\'') {
                    state = NORMAL;
                }
            } else { // state == IN_ESCAPE
                if (ch == '}') {
                    translateEscape(escape.toString(), result);
                    state = NORMAL;
                } else {
                    escape.append(ch);
                }
            }
        }

        if (state == IN_ESCAPE) {
            throw new SQLException("Syntax error in SQL escape syntax");
        }

        return result.toString();
    }

    /**
     * Translates the JDBC escape sequence into the database specific call.
     * 
     * @param escapeSequence the escapeSequence to translate
     * @param result the <code>StringBuffer</code> to write the results to
     */
    private static void translateEscape(String escapeSequence, StringBuffer result)
    throws SQLException {
        String str = escapeSequence.trim();
        int i = 0;
        int length = str.length();

        while (!Character.isWhitespace(str.charAt(i++)) && i < length);
        String escape = str.substring(0, i).toLowerCase();

        if (escape.equals(ESCAPE_PREFIX_FUNCTION)) {
            translateFunction(str, result);
        } else if (escape.equals(ESCAPE_PREFIX_CALL) || escape.startsWith("?")) {
            translateCall(str, result);
        } else if (escape.equals(ESCAPE_PREFIX_DATE)) {
            translateDate(str, result);
        } else if (escape.equals(ESCAPE_PREFIX_TIME)) {
            translateTime(str, result);
        } else if (escape.equals(ESCAPE_PREFIX_TIMESTAMP)) {
            translateTimestamp(str, result);
        } else if (escape.equals(ESCAPE_PREFIX_OUTER_JOIN)) {
            result.append(str.substring(ESCAPE_PREFIX_OUTER_JOIN.length()));
        } else if (escape.equals(ESCAPE_PREFIX_ESCAPE_CHAR)) {
            getEscape(str, result);
        } else {
            throw new SQLException("Unrecognized escape sequence: " + escapeSequence);
        }
    }

    /**
     * Translates JDBC functions
     */
    private static void translateFunction(String str, StringBuffer result)
    throws SQLException {
        str = str.substring(ESCAPE_PREFIX_FUNCTION.length());
        int pPos = str.indexOf('(');

        if (pPos < 0) {
            throw new SQLException("Malformed function escape, expected '(': " + str);
        } else if (str.charAt(str.length() - 1) != ')') {
            throw new SQLException("Malformed function escape, expected ')' at"
                                   + (str.length() - 1) + ": " + str);
        }

        String fName = str.substring(0, pPos).trim().toLowerCase();

        if (fName.equals("concat")) {
            translateConcat(str.substring(pPos), result);
        } else {
            fName = (String) _functionMap.get(fName);

            if (fName == null) {
                // The function name was not found in the map so just use the literal
                // string and let the database complain if it is invalid
                result.append(str);
            } else {
                // The funcation name was found in the map so use the translated name
                result.append(fName);
                result.append(str.substring(pPos));
            }
        }
    }

    /**
     * Returns the JDBC function CONCAT as a database concatenation string.
     * <p>
     * Per the specification, concatenation uses double quotes instead of single quotes
     * for string literals:
     * {fn CONCAT("Hot", "Java")}
     * {fn CONCAT(column1, "Java")}
     * {fn CONCAT("Hot", column2)}
     * {fn CONCAT(column1, column2)}
     * <p>
     * See: http://java.sun.com/j2se/1.3/docs/guide/jdbc/spec/jdbc-spec.frame11.html
     */
    private static void translateConcat(String str, StringBuffer result)
    throws SQLException {
        char[] chars = str.toCharArray();
        boolean inString = false;

        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];

            if (inString) {
                if (ch == '\'') {
                    // Single quotes must be escaped with another single quote
                    result.append("''");
                } else if (ch == '"' && chars[i] + 1 != '"') {
                    // What happens when ch = '"' && chars[i] + 1 == '"'
                    // Is this a case where a double quote is escaping a double quote?
                    result.append("'");
                    inString = false;
                } else {
                    result.append(ch);
                }
            } else {
                if (ch == ',') {
                    // {fn CONCAT("Hot", "Java")}
                    // The comma       ^  separating the parameters was found, simply
                    // replace it with a '+'.
                    result.append('+');
                } else if (ch == '"') {
                    result.append("'");
                    inString = true;
                } else {
                    result.append(ch);
                }
            }
        }
    }

    /**
     * Translates JDBC calls.
     */
    private static void translateCall(String str, StringBuffer result)
    throws SQLException {
        String escapeSequence = str;
        boolean returnsValue = str.startsWith("?");

        if (returnsValue) {
            int i = skipWhitespace(str, 1);

            if (str.charAt(i) != '=') {
                throw new SQLException("Malformed procedure call, '=' expected at "
                                       + i + ": " + escapeSequence);
            }

            i = skipWhitespace(str, i + 1);

            str = str.substring(i);

            if (!startsWithIgnoreCase(str, ESCAPE_PREFIX_CALL)) {
                throw new SQLException("Malformed procedure call, '"
                                       + ESCAPE_PREFIX_CALL + "' expected at "
                                       + i + ": " + escapeSequence);
            }
        }

        result.append("exec ");

        if (returnsValue) {
            result.append("?=");
        }

        str = str.substring(ESCAPE_PREFIX_CALL.length()).trim();

        int pPos = str.indexOf('(');

        if (pPos >= 0) {
            if (str.charAt(str.length() - 1) != ')') {
                throw new SQLException("Malformed procedure call, ')' expected at "
                                       + (escapeSequence.length() - 1) + ": "
                                       + escapeSequence);
            }

            result.append(str.substring(0, pPos));
            result.append(" ");
            result.append(str.substring(pPos + 1, str.length() - 1));
        } else {
            result.append(str);
        }
    }

    /**
     * Convert a JDBC SQL escape date sequence into a datestring recognized by SQLServer.
     */
    private static void translateDate(String str, StringBuffer result)
    throws SQLException {
        int i = 2; // skip over the "d "

        // skip any additional spaces
        i = skipWhitespace(str, i);
        i = skipQuote(str, i);

        // i is now up to the point where the date had better start.
        if (str.length() - i < 10
            || str.charAt(i + 4) != '-'
            || str.charAt(i + 7) != '-') {
            throw new SQLException("Malformed date");
        }

        String year = str.substring(i, i + 4);
        String month = str.substring(i + 5, i + 5 + 2);
        String day = str.substring(i + 5 + 3, i + 5 + 3 + 2);

        // Make sure the year, month, and day are numeric
        if (!validDigits(year) || !validDigits(month) || !validDigits(day)) {
            throw new SQLException("Malformed date");
        }

        // Make sure there isn't any garbage after the date
        i = i + 10;
        i = skipWhitespace(str, i);
        i = skipQuote(str, i);
        i = skipWhitespace(str, i);

        if (i < str.length()) {
            throw new SQLException("Malformed date");
        }

        result.append("'");
        result.append(year);
        result.append(month);
        result.append(day);
        result.append("'");
    }

    /**
     * Convert a JDBC SQL escape time sequence into a time string recognized by SQLServer.
     */
    private static void translateTime(String str, StringBuffer result)
    throws SQLException {
        int i = 2; // skip over the "t "

        // skip any additional spaces
        i = skipWhitespace(str, i);
        i = skipQuote(str, i);

        // i is now up to the point where the date had better start.
        if (str.length() - i < 8 || str.charAt(i + 2) != ':' || str.charAt(i + 5) != ':') {
            throw new SQLException("Malformed time");
        }

        String hour = str.substring(i, i + 2);
        String minute = str.substring(i + 3, i + 3 + 2);
        String second = str.substring(i + 3 + 3, i + 3 + 3 + 2);

        // Make sure the year, month, and day are numeric
        if (!validDigits(hour) || !validDigits(minute) || !validDigits(second)) {
            throw new SQLException("Malformed time");
        }

        // Make sure there isn't any garbage after the time
        i = i + 8;
        i = skipWhitespace(str, i);
        i = skipQuote(str, i);
        i = skipWhitespace(str, i);

        if (i < str.length()) {
            throw new SQLException("Malformed time");
        }

        result.append("'");
        result.append(hour);
        result.append(":");
        result.append(minute);
        result.append(":");
        result.append(second);
        result.append("'");
    }

    /**
     * Convert a JDBC SQL escape timestamp sequence into a date-time string recognized by SQLServer.
     */
    private static void translateTimestamp(String str, StringBuffer result)
    throws SQLException {
        int i = 3; // skip over the "ts "

        // skip any additional spaces
        i = skipWhitespace(str, i);
        i = skipQuote(str, i);

        // i is now at the point where the date had better start.
        if ((str.length() - i) < 19
            || str.charAt(i + 4) != '-'
            || str.charAt(i + 7) != '-') {
            throw new SQLException("Malformed date");
        }

        String year = str.substring(i, i + 4);
        String month = str.substring(i + 5, i + 5 + 2);
        String day = str.substring(i + 5 + 3, i + 5 + 3 + 2);

        // Make sure the year, month, and day are numeric
        if (!validDigits(year) || !validDigits(month) || !validDigits(day))
            throw new SQLException("Malformed date");

        // Make sure there is at least one space between date and time
        i = i + 10;

        if (!Character.isWhitespace(str.charAt(i))) {
            throw new SQLException("Malformed date");
        }

        // skip the whitespace
        i = skipWhitespace(str, i);

        // see if it could be a time
        if (str.length() - i < 8
            || str.charAt(i + 2) != ':'
            || str.charAt(i + 5) != ':') {
            throw new SQLException("Malformed time");
        }

        String hour = str.substring(i, i + 2);
        String minute = str.substring(i + 3, i + 3 + 2);
        String second = str.substring(i + 3 + 3, i + 3 + 3 + 2);
        String fraction = "000";

        i = i + 8;

        if (str.length() > i && str.charAt(i) == '.') {
            fraction = "";
            i++;

            while (str.length() > i && validDigits(str.substring(i, i + 1))) {
                fraction = fraction + str.substring(i,++i);
            }

            if (fraction.length() > 3) {
                fraction = fraction.substring(0, 3);
            } else {
                while (fraction.length() < 3) {
                    fraction = fraction + "0";
                }
            }
        }


        // Make sure there isn't any garbage after the time
        i = skipWhitespace(str, i);
        i = skipQuote(str, i);
        i = skipWhitespace(str, i);

        if (i<str.length()) {
            throw new SQLException("Malformed date");
        }

        result.append("'");
        result.append(year);
        result.append(month);
        result.append(day);
        result.append(" ");
        result.append(hour);
        result.append(":");
        result.append(minute);
        result.append(":");
        result.append(second);
        result.append(".");
        result.append(fraction);
        result.append("'");
    }

    /**
     * Returns ANSI ESCAPE sequence with the specified escape character
     */
    private static void getEscape(String str, StringBuffer result) throws SQLException {
        String tmpStr = str.substring(ESCAPE_PREFIX_ESCAPE_CHAR.length()).trim();

        // Escape string should be 3 characters long unless a single quote is being used
        // (which is probably a bad idea but the ANSI specification allows it) as an
        // escape character in which case the length should be 4.  The escape
        // sequence needs to be handled in this manner (with two single quotes) or else
        // parameters will not be counted properly as the string will remain open.
        if (!((tmpStr.length() == 3 && tmpStr.charAt(1) != '\'')
               || (tmpStr.length() == 4
                   && tmpStr.charAt(1) == '\''
                   && tmpStr.charAt(2) == '\''))
             || tmpStr.charAt(0) != '\''
             || tmpStr.charAt(tmpStr.length() - 1) != '\'') {
            throw new SQLException("Malformed escape: " + str);
        }

        result.append("ESCAPE ");
        result.append(tmpStr);
    }


    /**
     * Is the string made up only of digits?
     * <p>
     * <b>Note:</b>  Leading/trailing spaces or signs are not considered digits.
     *
     * @return true if the string has only digits, false otherwise.
     */
    private static boolean validDigits(String str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Given a string and an index into that string return the index
     * of the next non-whitespace character.
     *
     * @return index of next non-whitespace character.
     */
    private static int skipWhitespace(String str, int i) {
        while (i < str.length() && Character.isWhitespace(str.charAt(i))) {
            i++;
        }

        return i;
    }

    /**
     * Given a string and an index into that string, advanvance the index if it
     * is on a quote character.
     *
     * @return the next position in the string iff the current character is a
     *         quote
     */
    private static int skipQuote(String str, int i) {
        // skip over the leading quote if it exists
        if (i < str.length() && (str.charAt(i) == '\'' || str.charAt(i) == '"')) {
            // XXX Note-  The spec appears to prohibit the quote character,
            // but many drivers allow it.  We should probably control this
            // with a flag.
            i++;
        }

        return i;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        if (s.length() < prefix.length()) {
            return false;
        }

        for (int i = prefix.length() - 1; i >= 0; i--) {
            if (Character.toLowerCase(s.charAt(i)) != Character.toLowerCase(prefix.charAt(i))) {
                return false;
            }
        }

        return true;
    }
}
