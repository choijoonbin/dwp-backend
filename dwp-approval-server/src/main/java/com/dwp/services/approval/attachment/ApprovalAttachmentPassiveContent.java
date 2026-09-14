package com.dwp.services.approval.attachment;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

@Component
public class ApprovalAttachmentPassiveContent {
    public record Result(boolean allowed, String reason, String parserVersion) { }
    private static final Set<String> PDF_ACTIVE_KEYS=Set.of("AA","OpenAction","JS","JavaScript","Launch","EmbeddedFiles","EmbeddedFile","XFA","RichMedia","RichMediaContent","Rendition","SubmitForm","ImportData","URI","GoToR","GoToE","Sound","Movie");
    private static final Set<String> TYPES=Set.of("text/plain","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Set<String> ACTIVE_OOXML_ELEMENTS=Set.of("fldSimple","instrText","ddeLink","oleObject","externalLink","webExtension","altChunk","object",
            "f","definedName","calculatedColumnFormula","totalsRowFormula","formula","formula1","formula2","hyperlink","connection","queryTable");
    public Result inspect(byte[] bytes, String mediaType) {
        if (bytes.length<1 || bytes.length>25*1024*1024 || !TYPES.contains(mediaType)) return rejected("UNSUPPORTED_CONTENT_TYPE");
        try {
            if (mediaType.equals("application/pdf")) pdf(bytes);
            else if (mediaType.equals("text/plain")) text(bytes);
            else ooxml(bytes,mediaType);
            return new Result(true,"PASSIVE_CONTENT_ALLOWED_NOT_SANITIZED","PDFBox-3.0.8/OOXML-bounded-v1");
        } catch (Exception unsafe) { return rejected("ACTIVE_MALFORMED_OR_LIMITED_CONTENT"); }
    }
    private Result rejected(String reason) { return new Result(false,reason,"PDFBox-3.0.8/OOXML-bounded-v1"); }
    private void text(byte[] bytes) throws Exception {
        String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')) throw new IllegalArgumentException("Binary text.");
        // Plain text is never rendered inline. Markup is unsupported even when declared text/plain.
        if (value.contains("<") || value.contains(">")) throw new IllegalArgumentException("Markup text is not supported.");
    }
    private record Node(COSBase value, int depth) { }
    private void pdf(byte[] bytes) throws Exception {
        try (var document=Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getNumberOfPages()<1 || document.getNumberOfPages()>200 || document.getDocument().getXrefTable().size()>50000)
                throw new IllegalArgumentException("PDF hard limit.");
            var queue=new ArrayDeque<Node>();
            for (var key:document.getDocument().getXrefTable().keySet()) queue.add(new Node(document.getDocument().getObjectFromPool(key),0));
            queue.add(new Node(document.getDocumentCatalog().getCOSObject(),0));
            var seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
            int visited=0; long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
            while (!queue.isEmpty()) {
                var node=queue.removeFirst(); COSBase value=node.value();
                if (value==null || !seen.add(value)) continue;
                if (++visited>100000 || node.depth()>32 || System.nanoTime()>deadline) throw new IllegalArgumentException("PDF traversal limit.");
                if (value instanceof COSObject object) queue.add(new Node(object.getObject(),node.depth()+1));
                else if (value instanceof COSDictionary dictionary) {
                    for (var entry:dictionary.entrySet()) {
                        if (PDF_ACTIVE_KEYS.contains(entry.getKey().getName())) throw new IllegalArgumentException("Active PDF dictionary.");
                        if (entry.getValue() instanceof COSName name && (PDF_ACTIVE_KEYS.contains(name.getName()) || Set.of("Action","Filespec").contains(name.getName()))) throw new IllegalArgumentException("Active PDF object.");
                        queue.add(new Node(entry.getValue(),node.depth()+1));
                    }
                } else if (value instanceof COSArray array) for (int i=0;i<array.size();i++) queue.add(new Node(array.get(i),node.depth()+1));
            }
        }
    }
    private void ooxml(byte[] bytes, String type) throws Exception {
        String root=type.contains("wordprocessing")?"word/":type.contains("spreadsheet")?"xl/":"ppt/";
        var factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var names=new HashSet<String>(); int entries=0; long inflated=0; boolean types=false, main=false;
        try (var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if (++entries>1000 || name.length()>200 || name.startsWith("/") || name.contains("..") || name.contains("\\") || !names.add(name)) throw new IllegalArgumentException("Unsafe ZIP identity.");
                if (entry.isDirectory()) continue;
                String lower=name.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.contains("embeddings/") || lower.contains("activex/") || !(lower.endsWith(".xml") || lower.endsWith(".rels"))) throw new IllegalArgumentException("Unsupported active or binary OOXML part.");
                byte[] content=zip.readNBytes(2*1024*1024+1); inflated+=content.length;
                if (content.length>2*1024*1024 || inflated>50L*1024*1024 || inflated>Math.max(1024*1024,bytes.length*100L)) throw new IllegalArgumentException("ZIP expansion limit.");
                var parsed=factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
                var elements=parsed.getElementsByTagName("*");
                if (elements.getLength()>50000) throw new IllegalArgumentException("XML node limit.");
                for (int i=0;i<elements.getLength();i++) {
                    var element=(org.w3c.dom.Element)elements.item(i);
                    if ("External".equalsIgnoreCase(element.getAttribute("TargetMode")) || element.getAttribute("ContentType").toLowerCase(Locale.ROOT).contains("macroenabled")
                            || ACTIVE_OOXML_ELEMENTS.contains(element.getLocalName()==null?element.getNodeName():element.getLocalName())) throw new IllegalArgumentException("External, executable, or unsupported calculated OOXML content.");
                    int depth=0; org.w3c.dom.Node parent=element;
                    while ((parent=parent.getParentNode())!=null) if (++depth>32) throw new IllegalArgumentException("XML depth limit.");
                }
                if (name.equals("[Content_Types].xml")) types=true;
                if (name.equals(root+(root.equals("word/")?"document.xml":root.equals("xl/")?"workbook.xml":"presentation.xml"))) main=true;
            }
        }
        if (!types || !main || !names.contains("_rels/.rels")) throw new IllegalArgumentException("Incomplete OOXML package.");
    }
}
