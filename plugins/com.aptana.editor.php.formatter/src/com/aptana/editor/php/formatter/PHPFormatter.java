/**
 * This file Copyright (c) 2005-2010 Aptana, Inc. This program is
 * dual-licensed under both the Aptana Public License and the GNU General
 * Public license. You may elect to use one or the other of these licenses.
 * 
 * This program is distributed in the hope that it will be useful, but
 * AS-IS and WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, TITLE, or
 * NONINFRINGEMENT. Redistribution, except as permitted by whichever of
 * the GPL or APL you select, is prohibited.
 *
 * 1. For the GPL license (GPL), you can redistribute and/or modify this
 * program under the terms of the GNU General Public License,
 * Version 3, as published by the Free Software Foundation.  You should
 * have received a copy of the GNU General Public License, Version 3 along
 * with this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Aptana provides a special exception to allow redistribution of this file
 * with certain other free and open source software ("FOSS") code and certain additional terms
 * pursuant to Section 7 of the GPL. You may view the exception and these
 * terms on the web at http://www.aptana.com/legal/gpl/.
 * 
 * 2. For the Aptana Public License (APL), this program and the
 * accompanying materials are made available under the terms of the APL
 * v1.0 which accompanies this distribution, and is available at
 * http://www.aptana.com/legal/apl/.
 * 
 * You may view the GPL, Aptana's exception and additional terms, and the
 * APL in the file titled license.html at the root of the corresponding
 * plugin containing this source file.
 * 
 * Any modifications to this file must keep this entire header intact.
 */
package com.aptana.editor.php.formatter;

import static com.aptana.editor.php.formatter.PHPFormatterConstants.*;

import java.io.StringReader;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.osgi.util.NLS;
import org.eclipse.php.internal.core.ast.nodes.Program;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import com.aptana.editor.php.internal.parser.PHPMimeType;
import com.aptana.editor.php.internal.parser.PHPParser;
import com.aptana.editor.php.internal.parser.nodes.PHPASTWrappingNode;
import com.aptana.formatter.AbstractScriptFormatter;
import com.aptana.formatter.FormatterDocument;
import com.aptana.formatter.FormatterIndentDetector;
import com.aptana.formatter.FormatterWriter;
import com.aptana.formatter.IFormatterContext;
import com.aptana.formatter.IScriptFormatter;
import com.aptana.formatter.epl.FormatterPlugin;
import com.aptana.formatter.nodes.IFormatterContainerNode;
import com.aptana.formatter.ui.FormatterException;
import com.aptana.formatter.ui.FormatterMessages;
import com.aptana.formatter.ui.ScriptFormattingContextProperties;
import com.aptana.parsing.ast.IParseRootNode;
import com.aptana.parsing.ast.ParseNode;
import com.aptana.parsing.ast.ParseRootNode;
import com.aptana.ui.util.StatusLineMessageTimerManager;

/**
 * PHP code formatter.
 * 
 * @author Shalom Gibly <sgibly@aptana.com>
 */
public class PHPFormatter extends AbstractScriptFormatter implements IScriptFormatter
{

	/**
	 * Brace positions constants
	 */
	protected static final String[] BRACE_POSITIONS = { BRACE_POSITION_BLOCK, BRACE_POSITION_BLOCK_IN_CASE,
			BRACE_POSITION_BLOCK_IN_SWITCH, BRACE_POSITION_FUNCTION_DECLARATION, BRACE_POSITION_TYPE_DECLARATION };

	/**
	 * New-lines constants
	 */
	protected static final String[] NEW_LINES_POSITIONS = { NEW_LINES_BEFORE_CATCH_STATEMENT,
			NEW_LINES_BEFORE_DO_WHILE_STATEMENT, NEW_LINES_BEFORE_ELSE_STATEMENT,
			NEW_LINES_BEFORE_IF_IN_ELSEIF_STATEMENT };

	/**
	 * Indentation constants
	 */
	protected static final String[] INDENTATIONS = { INDENT_BLOCKS, INDENT_CASE_BODY, INDENT_SWITCH_BODY,
			INDENT_FUNCTION_BODY, INDENT_TYPE_BODY, INDENT_BREAK_IN_CASE };

	/**
	 * Spaces constants
	 */
	protected static final String[] SPACES = { SPACES_AFTER_METHOD_INVOCATION, SPACES_AFTER_STATIC_INVOCATION,
			SPACES_BEFORE_METHOD_INVOCATION, SPACES_BEFORE_STATIC_INVOCATION, SPACES_BEFORE_ASSIGNMENT,
			SPACES_AFTER_ASSIGNMENT, SPACES_BEFORE_COMMAS, SPACES_AFTER_COMMAS, SPACES_BEFORE_CASE_COLON,
			SPACES_AFTER_CASE_COLON, SPACES_BEFORE_COLON, SPACES_AFTER_COLON, SPACES_BEFORE_SEMICOLON,
			SPACES_AFTER_SEMICOLON, SPACES_BEFORE_DOT, SPACES_AFTER_DOT, SPACES_BEFORE_ARROW, SPACES_AFTER_ARROW };
	private static Pattern PHP_OPEN_TAG_PATTERNS = Pattern.compile("<\\?php|<\\?=|<%=|<\\?|<\\%"); //$NON-NLS-1$
	private String lineSeparator;

	/**
	 * Constructor.
	 * 
	 * @param preferences
	 */
	protected PHPFormatter(String lineSeparator, Map<String, ? extends Object> preferences, String mainContentType)
	{
		super(preferences, mainContentType);
		this.lineSeparator = lineSeparator;
	}

	/**
	 * Detects the indentation level.
	 */
	public int detectIndentationLevel(IDocument document, int offset)
	{
		int indent = 0;
		try
		{
			// detect the indentation offset with the parser, only if the given offset is not the first one in the
			// current
			// partition.
			ITypedRegion partition = document.getPartition(offset);
			if (partition != null && partition.getOffset() == offset)
			{
				return super.detectIndentationLevel(document, offset);
			}
			String source = document.get();
			PHPParser parser = (PHPParser) checkoutParser();
			Program ast = parser.parseAST(new StringReader(source));
			if (ast != null)
			{
				// we wrap the Program with a parser root node to match the API
				IParseRootNode rootNode = new ParseRootNode(PHPMimeType.MimeType, new ParseNode[0], ast.getStart(), ast
						.getEnd());
				rootNode.addChild(new PHPASTWrappingNode(ast));
				final PHPFormatterNodeBuilder builder = new PHPFormatterNodeBuilder();
				final FormatterDocument formatterDocument = createFormatterDocument(source, offset);
				IFormatterContainerNode root = builder.build(rootNode, formatterDocument);
				new PHPFormatterNodeRewriter(rootNode, formatterDocument).rewrite(root);
				IFormatterContext context = new PHPFormatterContext(0);
				FormatterIndentDetector detector = new FormatterIndentDetector(offset);
				try
				{
					root.accept(context, detector);
					return detector.getLevel();
				}
				catch (Exception e)
				{
					// ignore
				}
			}
		}
		catch (Throwable t)
		{
			return super.detectIndentationLevel(document, offset);
		}
		return indent;
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.formatter.ui.IScriptFormatter#format(java.lang.String, int, int, int)
	 */
	public TextEdit format(String source, int offset, int length, int indentationLevel) throws FormatterException
	{
		int offsetIncludedOpenTag = Math.max(0, findOpenTagOffset(source, offset));
		String input = source.substring(offsetIncludedOpenTag, offset + length);

		// We do not use a parse-state for the PHP, since we are just interested in the AST and do not want to update
		// anything in the indexing.
		try
		{
			PHPParser parser = (PHPParser) checkoutParser();
			Program ast = parser.parseAST(new StringReader(input));
			checkinParser(parser);
			if (ast != null)
			{
				// we wrap the Program with a parser root node to match the API
				IParseRootNode rootNode = new ParseRootNode(PHPMimeType.MimeType, new ParseNode[0], ast.getStart(), ast
						.getEnd());
				rootNode.addChild(new PHPASTWrappingNode(ast));
				final String output = format(input, rootNode, indentationLevel, offsetIncludedOpenTag);
				if (output != null)
				{
					if (!input.equals(output))
					{
						if (equalsIgnoreWhitespaces(input, output))
						{
							// We match the output to all possible PHP open-tags and then trim it to remove it with any
							// other white-space that appear before it.
							// For example, this output:
							// <?php
							// function foo() {}
							// Will be trimmed to:
							// <-- new-line
							// function foo() {}
							int inPHPOffset = 0;
							Matcher matcher = PHP_OPEN_TAG_PATTERNS.matcher(output);
							if (matcher.find())
							{
								inPHPOffset = matcher.end();
							}
							return new ReplaceEdit(offset, length, output.substring(inPHPOffset));
						}
						else
						{
							logError(input, output);
						}
					}
					else
					{
						return new MultiTextEdit(); // NOP
					}
				}
			}
		}
		catch (FormatterException e)
		{
			StatusLineMessageTimerManager.setErrorMessage(NLS.bind(
					FormatterMessages.Formatter_formatterParsingErrorStatus, e.getMessage()), ERROR_DISPLAY_TIMEOUT,
					true);
		}
		catch (Exception e)
		{
			StatusLineMessageTimerManager.setErrorMessage(FormatterMessages.Formatter_formatterErrorStatus,
					ERROR_DISPLAY_TIMEOUT, true);
			FormatterPlugin.logError(e);
		}
		return null;
	}

	/**
	 * Returns the offset of the PHP open tag that that precedes the given offset location.
	 * 
	 * @param source
	 * @param offset
	 * @return
	 */
	private int findOpenTagOffset(String source, int offset)
	{
		// We just look for the '<' char and that should cover all cases.
		for (; offset >= 0; offset--)
		{
			if (source.charAt(offset) == '<')
			{
				break;
			}
		}
		return offset;
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.formatter.ui.IScriptFormatter#getIndentSize()
	 */
	public int getIndentSize()
	{
		return getInt(FORMATTER_INDENTATION_SIZE);
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.formatter.ui.IScriptFormatter#getIndentType()
	 */
	public String getIndentType()
	{
		return getString(FORMATTER_TAB_CHAR);
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.formatter.ui.IScriptFormatter#getTabSize()
	 */
	public int getTabSize()
	{
		return getInt(FORMATTER_TAB_SIZE);
	}

	/**
	 * Do the actual formatting of the PHP.
	 * 
	 * @param input
	 *            The String input
	 * @param parseResult
	 *            A PHP parser result - {@link com.aptana.parsing.ast.IParseNode}
	 * @param indentationLevel
	 *            The indentation level to start from
	 * @return A formatted string
	 * @throws Exception
	 */
	private String format(String input, IParseRootNode parseResult, int indentationLevel, int offset) throws Exception
	{
		final PHPFormatterNodeBuilder builder = new PHPFormatterNodeBuilder();
		final FormatterDocument document = createFormatterDocument(input, offset);
		IFormatterContainerNode root = builder.build(parseResult, document);
		new PHPFormatterNodeRewriter(parseResult, document).rewrite(root);
		IFormatterContext context = new PHPFormatterContext(indentationLevel);
		FormatterWriter writer = new FormatterWriter(document, lineSeparator, createIndentGenerator());
		writer.setWrapLength(getInt(WRAP_COMMENTS_LENGTH));
		writer.setLinesPreserve(getInt(PRESERVED_LINES));
		root.accept(context, writer);
		writer.flush(context);
		// Unlike other formatters, we allow errors in the PHP AST for now.
		// We just notify the user that there were errors in the PHP file.
		if (builder.hasErrors())
		{
			StatusLineMessageTimerManager.setErrorMessage(
					FormatterMessages.Formatter_formatterErrorCompletedWithErrors, ERROR_DISPLAY_TIMEOUT, true);
		}
		return writer.getOutput();
	}

	private FormatterDocument createFormatterDocument(String input, int offset)
	{
		FormatterDocument document = new FormatterDocument(input);
		document.setInt(FORMATTER_TAB_SIZE, getInt(FORMATTER_TAB_SIZE));
		document.setBoolean(WRAP_COMMENTS, getBoolean(WRAP_COMMENTS));
		document.setInt(LINES_AFTER_TYPE_DECLARATION, getInt(LINES_AFTER_TYPE_DECLARATION));
		document.setInt(LINES_AFTER_FUNCTION_DECLARATION, getInt(LINES_AFTER_FUNCTION_DECLARATION));
		document.setInt(ScriptFormattingContextProperties.CONTEXT_ORIGINAL_OFFSET, offset);

		// Set the indentation values
		for (String key : INDENTATIONS)
		{
			document.setBoolean(key, getBoolean(key));
		}
		// Set the new-lines values
		for (String key : NEW_LINES_POSITIONS)
		{
			document.setBoolean(key, getBoolean(key));
		}
		// Set the braces values
		for (String key : BRACE_POSITIONS)
		{
			document.setString(key, getString(key));
		}
		// Set the spaces values
		for (String key : SPACES)
		{
			document.setInt(key, getInt(key));
		}
		return document;
	}
}
