//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   S c o r e E x p o r t e r                                    //
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
package org.audiveris.omr.score;

import org.audiveris.omr.OMR;
import org.audiveris.omr.score.PartwiseBuilder.Result;
import org.audiveris.omr.util.CustomXMLStreamWriter;
import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.mxl.Mxl;
import org.audiveris.proxymusic.mxl.RootFile;
import org.audiveris.proxymusic.util.Marshalling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Node;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;

import java.awt.Rectangle;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

/**
 * Class <code>ScoreExporter</code> exports the provided score to a MusicXML file, stream or
 * DOM.
 *
 * @author Hervé Bitteur
 */
public class ScoreExporter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ScoreExporter.class);

    private static final String HITBOX_NAMESPACE = "http://audiveris.org/omr-data";
    private static final String HITBOX_PREFIX = "omr";

    //~ Instance fields ----------------------------------------------------------------------------

    /** The related score. */
    private final Score score;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Create a new <code>ScoreExporter</code> object, on a related score instance.
     *
     * @param score the score to export (cannot be null)
     */
    public ScoreExporter (Score score)
    {
        if (score == null) {
            throw new IllegalArgumentException("Trying to export a null score");
        }

        this.score = score;
    }

    //~ Methods ------------------------------------------------------------------------------------

    //--------//
    // export //
    //--------//
    /**
     * Export the score to DOM node.
     * (No longer used, it was meant for Audiveris&rarr;Zong pure java transfer)
     *
     * @param node   the DOM node to export to (cannot be null)
     * @param signed should we inject ProxyMusic signature?
     * @throws Exception if something goes wrong
     */
    public void export (Node node,
                        boolean signed)
        throws Exception
    {
        if (node == null) {
            throw new IllegalArgumentException("Trying to export a score to a null DOM Node");
        }

        // Build the ScorePartwise proxy
        ScorePartwise scorePartwise = PartwiseBuilder.build(score);

        // Marshal the proxy
        Marshalling.marshal(scorePartwise, node, signed);
    }

    //--------//
    // export //
    //--------//
    /**
     * Export the score to an output stream.
     *
     * @param os         the output stream where XML data is written (cannot be null)
     * @param signed     should we inject ProxyMusic signature?
     * @param scoreName  (for compressed only) simple score name, without extension
     * @param compressed true for compressed output
     * @throws Exception if something goes wrong
     */
    public void export (OutputStream os,
                        boolean signed,
                        String scoreName,
                        boolean compressed)
        throws Exception
    {
        Objects.requireNonNull(os, "Trying to export a score to a null output stream");

        // Build the ScorePartwise proxy together with note hit boxes
        Result buildResult = PartwiseBuilder.buildResult(score);
        ScorePartwise scorePartwise = buildResult.scorePartwise();
        List<Rectangle> noteHitBoxes = buildResult.noteHitBoxes();

        // Marshal the proxy
        if (compressed) {
            Mxl.Output mof = new Mxl.Output(os);
            OutputStream zos = mof.getOutputStream();

            if (scoreName == null) {
                scoreName = "score"; // Fall-back value
            }

            mof.addEntry(
                    new RootFile(scoreName + OMR.SCORE_EXTENSION, RootFile.MUSICXML_MEDIA_TYPE));
            marshalScore(zos, scorePartwise, signed, noteHitBoxes);
            mof.close();
        } else {
            try (os) {
                marshalScore(os, scorePartwise, signed, noteHitBoxes);
            }
        }
    }

    //--------//
    // export //
    //--------//
    /**
     * Export the score to a file.
     *
     * @param path       the xml or mxl path to write (cannot be null)
     * @param scoreName  simple score name, without extension
     * @param signed     should we inject ProxyMusic signature?
     * @param compressed true for compressed output, false for uncompressed output
     * @throws Exception if something goes wrong
     */
    public void export (Path path,
                        String scoreName,
                        boolean signed,
                        boolean compressed)
        throws Exception
    {
        try (OutputStream os = new FileOutputStream(path.toString())) {
            export(os, signed, scoreName, compressed);
            logger.info("Score {} exported to {}", scoreName, path);
        }
    }

    //--------------//
    // marshalScore //
    //--------------//
    private void marshalScore (OutputStream os,
                               ScorePartwise scorePartwise,
                               boolean signed,
                               List<Rectangle> noteHitBoxes)
        throws Exception
    {
        annotateScore(scorePartwise, signed);

        JAXBContext context = Marshalling.getContext(ScorePartwise.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        XMLStreamWriter baseWriter = XMLOutputFactory.newInstance().createXMLStreamWriter(os, "UTF-8");
        CustomXMLStreamWriter writer = new CustomXMLStreamWriter(
                baseWriter,
                "  ",
                noteHitBoxes,
                HITBOX_PREFIX,
                HITBOX_NAMESPACE);
        marshaller.marshal(scorePartwise, writer);
        writer.flush();
    }

    //---------------//
    // annotateScore //
    //---------------//
    private void annotateScore (ScorePartwise scorePartwise,
                                boolean signed)
    {
        try {
            Method method = Marshalling.class.getDeclaredMethod(
                    "annotate",
                    ScorePartwise.class,
                    boolean.class);
            method.setAccessible(true);
            method.invoke(null, scorePartwise, signed);
        } catch (ReflectiveOperationException ex) {
            logger.warn("Unable to annotate score before exporting", ex);
        }
    }
}
