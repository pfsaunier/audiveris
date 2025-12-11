//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                            C u s t o m X M L S t r e a m W r i t e r                           //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2025. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.util;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Class <code>CustomXMLStreamWriter</code> handles indentation for XML output.
 * <p>
 * It handles indentation correctly (without JAXB default indentation limited to 8 steps).
 * <p>
 * Any element detected as empty (that is element with no content) is written as one self-closing
 * empty element rather than the pair of start + end tags.
 *
 * @author Kohsuke Kawaguchi (the author of the internal Sun implementation of class
 *         IndentingXMLStreamWriter in com.sun.xml.internal.txw2.output package, this class was
 *         initially derived from)
 * @author Luca Basso Ricci
 * @see <a href="https://stackoverflow.com/a/27158805">Luca article</a>
 * @author Hervé Bitteur (buffering of every element item as a Callable)
 */
public class CustomXMLStreamWriter
        implements XMLStreamWriter
{
    //~ Instance fields ----------------------------------------------------------------------------

    /** The actual writer, to which any real work is delegated. */
    protected final XMLStreamWriter writer;

    /** The indentation amount for one step. If null, no indentation is performed at all. */
    protected final String indentStep;

    /** Current level of element indentation. */
    protected int level;

    /** Are we closing element(s)?. */
    protected boolean closing;

    /**
     * Pending element processing with related items, such as attributes.
     * <ol>
     * <li>At index 0: the 'writeEmptyElement'
     * <li>At index 1: the 'writeStartElement'
     * <li>At following indices, depending on order of arrival, we can find:
     * <ul>
     * <li>'writeAttribute'
     * <li>'writeNameSpace'
     * <li>'setPrefix'
     * <li>'setDefaultNameSpace'
     * </ul>
     * </ol>
     */
    protected List<Item> items = new ArrayList<>();

    /** Optional sequence of hit boxes aligned with successive note elements. */
    private final List<Rectangle> noteHitBoxes;

    /** Prefix used for custom namespace insertion. */
    private final String hitboxPrefix;

    /** Namespace used for custom insertion. */
    private final String hitboxNamespace;

    /** Do we have any hit box to export? */
    private final boolean hasHitboxData;

    /** Has the namespace already been declared in the document? */
    private boolean hitboxNamespaceDeclared;

    /** Index of the next note to annotate. */
    private int noteIndex;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>IndentingXmlStreamWriter</code> object with default indent step of
     * 2 spaces.
     *
     * @param writer the underlying writer
     */
    public CustomXMLStreamWriter (XMLStreamWriter writer)
    {
        this(writer, "  ");
    }

    /**
     * Creates a new <code>IndentingXmlStreamWriter</code> object, with the specified indent
     * step value.
     *
     * @param writer     the underlying writer
     * @param indentStep the indentation string for one step. If null, no indentation is performed.
     */
    public CustomXMLStreamWriter (XMLStreamWriter writer,
                                  final String indentStep)
    {
        this(writer, indentStep, null, null, null);
    }

    /**
     * Creates a new <code>IndentingXmlStreamWriter</code> object configured to emit
     * additional hitbox information for note elements.
     *
     * @param writer          the underlying writer
     * @param indentStep      indentation string for one step. If null, no indentation is performed.
     * @param noteHitBoxes    sequence of note hit boxes aligned on note order
     * @param hitboxPrefix    prefix for the custom namespace
     * @param hitboxNamespace namespace URI for hitbox elements
     */
    public CustomXMLStreamWriter (XMLStreamWriter writer,
                                  final String indentStep,
                                  final List<Rectangle> noteHitBoxes,
                                  final String hitboxPrefix,
                                  final String hitboxNamespace)
    {
        this.writer = writer;
        this.indentStep = indentStep;
        this.noteHitBoxes = noteHitBoxes;
        this.hitboxPrefix = hitboxPrefix;
        this.hitboxNamespace = hitboxNamespace;
        this.hasHitboxData = hasNonNullHitBoxes(noteHitBoxes) && (hitboxPrefix != null)
                && (hitboxNamespace != null);
    }

    //~ Methods ------------------------------------------------------------------------------------

    @Override
    public void close ()
        throws XMLStreamException
    {
        flushItems();
        writer.close();
    }

    //----------//
    // doIndent //
    //----------//
    /**
     * Insert a new line, followed by proper level of indentation.
     *
     * @throws XMLStreamException if anything goes wrong
     */
    protected void doIndent ()
        throws XMLStreamException
    {
        if (indentStep != null) {
            writer.writeCharacters("\n");

            for (int i = 0; i < level; i++) {
                writer.writeCharacters(indentStep);
            }
        }
    }

    @Override
    public void flush ()
        throws XMLStreamException
    {
        if (items.isEmpty()) {
            writer.flush();
        }
    }

    //------------//
    // flushItems //
    //------------//
    /**
     * We finish the saving of current element if any, by flushing the saved items.
     *
     * @throws XMLStreamException if anything goes wrong
     */
    protected void flushItems ()
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            try {
                // Write 'start' element
                items.get(1).call(); // At index 1 was saved the 'start' processing

                // Process the saved items
                for (Item item : items.subList(2, items.size())) {
                    item.call();
                }
            } catch (Exception ex) {
                throw new XMLStreamException(ex);
            }

            items.clear();
        }
    }

    @Override
    public NamespaceContext getNamespaceContext ()
    {
        return writer.getNamespaceContext();
    }

    @Override
    public String getPrefix (final String uri)
        throws XMLStreamException
    {
        return writer.getPrefix(uri);
    }

    @Override
    public Object getProperty (final String name)
        throws IllegalArgumentException
    {
        return writer.getProperty(name);
    }

    //---------------//
    // indentComment //
    //---------------//
    /**
     * Indentation before comment. Always indent.
     *
     * @throws XMLStreamException if anything goes wrong
     */
    protected void indentComment ()
        throws XMLStreamException
    {
        if (indentStep != null) {
            doIndent();
        }
    }

    //-----------//
    // indentEnd //
    //-----------//
    /**
     * Indentation before end tag. Indent except on first close.
     *
     * @throws XMLStreamException if anything goes wrong
     */
    protected void indentEnd ()
        throws XMLStreamException
    {
        if (indentStep != null) {
            level--;

            if (closing) {
                doIndent();
            }

            closing = true;
        }
    }

    //-------------//
    // indentStart //
    //-------------//
    /**
     * Indentation before start tag. Always indent.
     *
     * @param localName the local tag name.
     *                  It can be used by an overriding implementation to decide to include
     *                  on-the-fly any material such as a specific comment.
     * @throws XMLStreamException if anything goes wrong
     */
    protected void indentStart (final String localName)
        throws XMLStreamException
    {
        if (indentStep != null) {
            doIndent();
            level++;
            closing = false;
        }
    }

    @Override
    public void setDefaultNamespace (final String uri)
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            items.add( () ->
            {
                writer.setDefaultNamespace(uri);

                return null;
            });
        } else {
            writer.setDefaultNamespace(uri);
        }
    }

    @Override
    public void setNamespaceContext (NamespaceContext context)
        throws XMLStreamException
    {
        writer.setNamespaceContext(context);
    }

    @Override
    public void setPrefix (final String prefix,
                           final String uri)
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            items.add( () ->
            {
                writer.setPrefix(prefix, uri);

                return null;
            });
        } else {
            writer.setPrefix(prefix, uri);
        }
    }

    @Override
    public void writeAttribute (final String localName,
                                final String value)
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            items.add( () ->
            {
                writer.writeAttribute(localName, value);

                return null;
            });
        } else {
            writer.writeAttribute(localName, value);
        }
    }

    @Override
    public void writeAttribute (final String namespaceURI,
                                final String localName,
                                final String value)
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            items.add( () ->
            {
                writer.writeAttribute(namespaceURI, localName, value);

                return null;
            });
        } else {
            writer.writeAttribute(namespaceURI, localName, value);
        }
    }

    @Override
    public void writeAttribute (final String prefix,
                                final String namespaceURI,
                                final String localName,
                                final String value)
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            items.add( () ->
            {
                writer.writeAttribute(prefix, namespaceURI, localName, value);

                return null;
            });
        } else {
            writer.writeAttribute(prefix, namespaceURI, localName, value);
        }
    }

    @Override
    public void writeCData (final String data)
        throws XMLStreamException
    {
        if (!data.isEmpty()) {
            flushItems();
            writer.writeCData(data);
        }
    }

    @Override
    public void writeCharacters (char[] text,
                                 int start,
                                 int len)
        throws XMLStreamException
    {
        if (len > 0) {
            flushItems();
            writer.writeCharacters(text, start, len);
        }
    }

    @Override
    public void writeCharacters (final String text)
        throws XMLStreamException
    {
        if (!text.isEmpty()) {
            flushItems();
            writer.writeCharacters(text);
        }
    }

    @Override
    public void writeComment (final String data)
        throws XMLStreamException
    {
        flushItems();
        indentComment();
        writer.writeComment(data);
    }

    @Override
    public void writeDefaultNamespace (final String namespaceURI)
        throws XMLStreamException
    {
        writer.writeDefaultNamespace(namespaceURI);
    }

    @Override
    public void writeDTD (final String dtd)
        throws XMLStreamException
    {
        flushItems();
        writer.writeDTD(dtd);
    }

    @Override
    public void writeEmptyElement (final String localName)
        throws XMLStreamException
    {
        flushItems();
        writer.writeEmptyElement(localName);
    }

    @Override
    public void writeEmptyElement (final String namespaceURI,
                                   final String localName)
        throws XMLStreamException
    {
        flushItems();
        writer.writeEmptyElement(namespaceURI, localName);
    }

    @Override
    public void writeEmptyElement (final String prefix,
                                   final String localName,
                                   final String namespaceURI)
        throws XMLStreamException
    {
        flushItems();
        writer.writeEmptyElement(prefix, localName, namespaceURI);
    }

    @Override
    public void writeEndDocument ()
        throws XMLStreamException
    {
        flushItems();
        writer.writeEndDocument();
    }

    @Override
    public void writeEndElement ()
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            try {
                // Here, element has no content (attributes don't count as content),
                // therefore we write empty element, instead of start + end
                items.get(0).call(); // At index 0 was saved the empty processing

                // Process the saved items
                for (Item item : items.subList(2, items.size())) {
                    item.call();
                }
            } catch (Exception ex) {
                throw new XMLStreamException(ex);
            }

            indentEnd();
            items.clear();
        } else {
            indentEnd();
            writer.writeEndElement();
        }
    }

    @Override
    public void writeEntityRef (final String name)
        throws XMLStreamException
    {
        flushItems();
        writer.writeEntityRef(name);
    }

    @Override
    public void writeNamespace (final String prefix,
                                final String namespaceURI)
        throws XMLStreamException
    {
        if (!items.isEmpty()) {
            items.add( () ->
            {
                writer.writeNamespace(prefix, namespaceURI);

                return null;
            });
        } else {
            writer.writeNamespace(prefix, namespaceURI);
        }
    }

    @Override
    public void writeProcessingInstruction (final String target)
        throws XMLStreamException
    {
        flushItems();
        writer.writeProcessingInstruction(target);
    }

    @Override
    public void writeProcessingInstruction (final String target,
                                            final String data)
        throws XMLStreamException
    {
        flushItems();
        writer.writeProcessingInstruction(target, data);
    }

    @Override
    public void writeStartDocument ()
        throws XMLStreamException
    {
        writer.writeStartDocument();
    }

    @Override
    public void writeStartDocument (final String version)
        throws XMLStreamException
    {
        writer.writeStartDocument(version);
    }

    @Override
    public void writeStartDocument (final String encoding,
                                    final String version)
        throws XMLStreamException
    {
        writer.writeStartDocument(encoding, version);
    }

    @Override
    public void writeStartElement (final String localName)
        throws XMLStreamException
    {
        flushItems();
        indentStart(localName);

        items.add( () ->
        {
            writer.writeEmptyElement(localName); // Empty saved first

            return null;
        });
        items.add( () ->
        {
            writer.writeStartElement(localName); // Start saved second
            afterStartElement(null, localName);

            return null;
        });
    }

    @Override
    public void writeStartElement (final String namespaceURI,
                                   final String localName)
        throws XMLStreamException
    {
        flushItems();
        indentStart(localName);

        items.add( () ->
        {
            writer.writeEmptyElement(namespaceURI, localName); // Empty saved first

            return null;
        });
        items.add( () ->
        {
            writer.writeStartElement(namespaceURI, localName); // Start saved second
            afterStartElement(namespaceURI, localName);

            return null;
        });
    }

    @Override
    public void writeStartElement (final String prefix,
                                   final String localName,
                                   final String namespaceURI)
        throws XMLStreamException
    {
        flushItems();
        indentStart(localName);

        items.add( () ->
        {
            writer.writeEmptyElement(prefix, localName, namespaceURI); // Empty saved first

            return null;
        });
        items.add( () ->
        {
            writer.writeStartElement(prefix, localName, namespaceURI); // Start saved second
            afterStartElement(namespaceURI, localName);

            return null;
        });
    }

    //~ Inner Interfaces ---------------------------------------------------------------------------

    //--------------------//
    // afterStartElement //
    //--------------------//
    private void afterStartElement (String namespaceURI,
                                    String localName)
        throws XMLStreamException
    {
        if (hasHitboxData && !hitboxNamespaceDeclared && "score-partwise".equals(localName)) {
            writer.writeNamespace(hitboxPrefix, hitboxNamespace);
            hitboxNamespaceDeclared = true;
        }

        if (!hasHitboxData || !"note".equals(localName)) {
            return;
        }

        Rectangle hitBox = nextHitBox();

        if (hitBox != null) {
            writeHitbox(hitBox);
        }
    }

    //----------------//
    // hasNonNullHitBoxes //
    //----------------//
    private static boolean hasNonNullHitBoxes (List<Rectangle> boxes)
    {
        if (boxes == null) {
            return false;
        }

        for (Rectangle rect : boxes) {
            if (rect != null) {
                return true;
            }
        }

        return false;
    }

    //-------------//
    // nextHitBox  //
    //-------------//
    private Rectangle nextHitBox ()
    {
        Rectangle hitBox = null;

        if ((noteHitBoxes != null) && (noteIndex < noteHitBoxes.size())) {
            hitBox = noteHitBoxes.get(noteIndex);
        }

        noteIndex++;

        return hitBox;
    }

    //------------//
    // writeHitbox //
    //------------//
    private void writeHitbox (Rectangle hitBox)
        throws XMLStreamException
    {
        if (indentStep != null) {
            doIndent();
        }

        writer.writeEmptyElement(hitboxPrefix, "hitbox", hitboxNamespace);
        writer.writeAttribute("x", Integer.toString(hitBox.x));
        writer.writeAttribute("y", Integer.toString(hitBox.y));
        writer.writeAttribute("width", Integer.toString(hitBox.width));
        writer.writeAttribute("height", Integer.toString(hitBox.height));
        closing = false;
    }

    //------//
    // Item //
    //------//
    /**
     * Interface meant to save the processing of an item (such as attribute) related to
     * the current element.
     */
    protected static interface Item
            extends Callable<Void>
    {
    }
}
