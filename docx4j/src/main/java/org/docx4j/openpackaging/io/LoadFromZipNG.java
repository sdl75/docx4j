/*
 *  Copyright 2007-2009, Plutext Pty Ltd.
 *   
 *  This file is part of docx4j.

    docx4j is licensed under the Apache License, Version 2.0 (the "License"); 
    you may not use this file except in compliance with the License. 

    You may obtain a copy of the License at 

        http://www.apache.org/licenses/LICENSE-2.0 

    Unless required by applicable law or agreed to in writing, software 
    distributed under the License is distributed on an "AS IS" BASIS, 
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
    See the License for the specific language governing permissions and 
    limitations under the License.

 */

package org.docx4j.openpackaging.io;



import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.apache.log4j.Logger;
import org.docx4j.jaxb.Context;
import org.docx4j.model.datastorage.CustomXmlDataStorage;
import org.docx4j.openpackaging.Base;
import org.docx4j.openpackaging.URIHelper;
import org.docx4j.openpackaging.contenttype.ContentType;
import org.docx4j.openpackaging.contenttype.ContentTypeManager;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.exceptions.InvalidFormatException;
import org.docx4j.openpackaging.exceptions.PartUnrecognisedException;
import org.docx4j.openpackaging.packages.Package;
import org.docx4j.openpackaging.parts.DefaultXmlPart;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPart;
import org.docx4j.openpackaging.parts.relationships.Namespaces;
import org.docx4j.openpackaging.parts.relationships.RelationshipsPart;
import org.docx4j.relationships.Relationships;
import org.docx4j.relationships.Relationship;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;


/**
 * Create a Package object from a Zip file or input stream.
 * 
 * This class is a refactoring of LoadFromZipFile, which
 * couldn't read from an input stream
 * 
 * @author jharrop
 * 
 */
public class LoadFromZipNG extends Load {
	
	public HashMap<String, ByteArray> partByteArrays = new HashMap<String, ByteArray>();	
	
	private static Logger log = Logger.getLogger(LoadFromZipNG.class);

	// Testing
	public static void main(String[] args) throws Exception {
		String filepath = System.getProperty("user.dir") + "/sample-docs/FontEmbedded.docx";
		log.info("Path: " + filepath );
		LoadFromZipNG loader = new LoadFromZipNG();
		loader.get(filepath);		
	}

	 // HashMap containing the names of all the zip entries,
	// so we can tell whether there are any orphans
	public HashMap unusedZipEntries = new HashMap();
	
	public LoadFromZipNG() {
		this(new ContentTypeManager() );
	}

	public LoadFromZipNG(ContentTypeManager ctm) {
		this.ctm = ctm;
	}
	
	
	public Package get(String filepath) throws Docx4JException {
		return get(new File(filepath));
	}
	
	public static byte[] getBytesFromInputStream(InputStream is)
		throws Exception {
		
		BufferedInputStream bufIn = new BufferedInputStream(is);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BufferedOutputStream bos = new BufferedOutputStream(baos);
		int c = bufIn.read();
		while (c != -1) {
			bos.write(c);
			c = bufIn.read();
		}
		bos.flush();
		baos.flush();		
		//bufIn.close(); //don't do that, since it closes the ZipInputStream after we've read an entry!
		bos.close();
		return baos.toByteArray();
	} 			
	
	public Package get(File f) throws Docx4JException {
		log.info("Filepath = " + f.getPath() );
		
		ZipFile zf = null;
		try {
			if (!f.exists()) {
				log.info( "Couldn't find " + f.getPath() );
			}
			zf = new ZipFile(f);
		} catch (IOException ioe) {
			ioe.printStackTrace() ;
			throw new Docx4JException("Couldn't get ZipFile", ioe);
		}
				
		Enumeration entries = zf.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = (ZipEntry) entries.nextElement();
			log.info( "\n\n" + entry.getName() + "\n" );
			InputStream in = null;
			try {			
				byte[] bytes =  getBytesFromInputStream( zf.getInputStream(entry) );
				partByteArrays.put(entry.getName(), new ByteArray(bytes) );
			} catch (Exception e) {
				e.printStackTrace() ;
			}	
		}
		 // At this point, we've finished with the zip file
		 try {
			 zf.close();
		 } catch (IOException exc) {
			 exc.printStackTrace();
		 }
		 
		
		return process();
	}

	public Package get(InputStream is) throws Docx4JException {

       try {
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
				byte[] bytes =  getBytesFromInputStream( zis );
				//log.debug("Extracting " + entry.getName());
				partByteArrays.put(entry.getName(), new ByteArray(bytes) );
            }
            zis.close();
        } catch (Exception e) {
            log.error(e.getMessage());
        }	
	        
		// At this point, we're finished with the zip input stream
        // TODO, so many of the below methods could be renamed.
        // If performance is ok, LoadFromJCR could be refactored to
        // work the same way
		
		return process();
	}
	
	private Package process() throws Docx4JException {

		// 2. Create a new Package
		//		Eventually, you'll also be able to create an Excel package etc
		//		but only the WordML package exists at present
		
		Document ctmDocument = null;
		try {
			ctmDocument = deprecatedGetDocumentFromZippedPart(partByteArrays, "[Content_Types].xml");
		} catch (Exception e) {
			// Shouldn't happen
			throw new Docx4JException("Couldn't get [Content_Types].xml", e);
		}
		debugPrint(ctmDocument);
		ctm.parseContentTypesFile(ctmDocument);		
		Package p = ctm.createPackage();
		
		// 3. Get [Content_Types].xml
//		Once we've got this, then we can look up the content type for
//		each PartName, and use it in the Part constructor.
//		p.setContentTypeManager(ctm); - 20080111 - done by ctm.createPackage();
		unusedZipEntries.put("[Content_Types].xml", new Boolean(false));
		
		// 4. Start with _rels/.rels

//		<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
//		  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
//		  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
//		  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
//		</Relationships>		
		
		String partName = "_rels/.rels";
		RelationshipsPart rp = getRelationshipsPartFromZip(p, partByteArrays,  partName);		
		p.setRelationships(rp);
		//rp.setPackageRelationshipPart(true);		
		unusedZipEntries.put(partName, new Boolean(false));
		
		
		log.info( "Object created for: " + partName);
		//log.info( rp.toString());
		
		// 5. Now recursively 
//		(i) create new Parts for each thing listed
//		in the relationships
//		(ii) add the new Part to the package
//		(iii) cross the PartName off unusedZipEntries
		addPartsFromRelationships(partByteArrays, p, rp );
		
		
		// 6. Check unusedZipEntries is empty
		 Iterator myVeryOwnIterator = unusedZipEntries.keySet().iterator();
		 while(myVeryOwnIterator.hasNext()) {
		     String key = (String)myVeryOwnIterator.next();
		     log.info( key + "  " + unusedZipEntries.get(key));
		 }
		 
		registerCustomXmlDataStorageParts(p);
		 
		 return p;
	}
	
	//private RelationshipsPart getRelationshipsPartFromZip(Base p, ZipFile zf, String partName) 
	private RelationshipsPart getRelationshipsPartFromZip(Base p, HashMap<String, ByteArray> partByteArrays, String partName) 
			throws Docx4JException {
//			Document contents = null;
//			try {
//				contents = getDocumentFromZippedPart( zf,  partName);
//			} catch (Exception e) {
//				e.printStackTrace();
//				throw new Docx4JException("Error getting document from Zipped Part", e);
//				
//			} 
//		// debugPrint(contents);
//		// TODO - why don't any of the part names in this document start with "/"?
//		return new RelationshipsPart( p, new PartName("/" + partName), contents );	
		
		RelationshipsPart rp = null;
		
		InputStream is = null;
		try {
			is =  getInputStreamFromZippedPart( partByteArrays,  partName);
			//thePart = new RelationshipsPart( p, new PartName("/" + partName), is );
			rp = new RelationshipsPart(new PartName("/" + partName) );
			rp.setSourceP(p);
			rp.unmarshal(is);
			
		} catch (Exception e) {
			e.printStackTrace();
			throw new Docx4JException("Error getting document from Zipped Part:" + partName, e);
			
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException exc) {
					exc.printStackTrace();
				}
			}
		}
		
		return rp;
	// debugPrint(contents);
	// TODO - why don't any of the part names in this document start with "/"?
	}

	private static InputStream getInputStreamFromZippedPart(HashMap<String, ByteArray> partByteArrays,
			String partName) 
	//private static InputStream getInputStreamFromZippedPart(ZipFile zf, String partName) 
		throws DocumentException, IOException {
		
		InputStream in = null;
		//in = zf.getInputStream( zf.getEntry(partName ) );
		in = partByteArrays.get(partName).getInputStream();
		return in;		
	}
	
	
	private static Document deprecatedGetDocumentFromZippedPart(HashMap<String, ByteArray> partByteArrays, 
			String partName)
	//private static Document deprecatedGetDocumentFromZippedPart(ZipFile zf, String partName) 
		throws DocumentException, IOException {
		
		InputStream in = null;
//		in = zf.getInputStream( zf.getEntry(partName ) );
		in = partByteArrays.get(partName).getInputStream();
		SAXReader xmlReader = new SAXReader();
		Document contents = null;
		try {
			contents = xmlReader.read(in);
		} catch (DocumentException e) {
			// Will land here for binary files eg gif file
			// These do get handled ..
			log.error("DocumentException on " + partName + " . Check this is binary content."); 
			//e.printStackTrace() ;
			throw e;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException exc) {
					exc.printStackTrace();
				}
			}
		}
		return contents;		
	}
	
	/* recursively 
	(i) create new Parts for each thing listed
	in the relationships
	(ii) add the new Part to the package
	(iii) cross the PartName off unusedZipEntries
	*/
	//private void addPartsFromRelationships(ZipFile zf, Base source, RelationshipsPart rp)
	private void addPartsFromRelationships(HashMap<String, ByteArray> partByteArrays, 
			Base source, RelationshipsPart rp)
		throws Docx4JException {
		
		Package pkg = source.getPackage();				
		
//		for (Iterator it = rp.iterator(); it.hasNext(); ) {
//			Relationship r = (Relationship)it.next();
//			log.info("For Relationship Id=" + r.getId() + " Source is " 
//					+ r.getSource().getPartName() 
//					+ ", Target is " + r.getTargetURI() );
//			try {
//				
//				getPart(zf, pkg, rp, r);
//				
//			} catch (Exception e) {
//				throw new Docx4JException("Failed to add parts from relationships", e);
//			}
//		}
		
		for ( Relationship r : rp.getRelationships().getRelationship() ) {
			
			log.info("\n For Relationship Id=" + r.getId() 
					+ " Source is " + rp.getSourceP().getPartName() 
					+ ", Target is " + r.getTarget() );
				// This is usually the first logged comment for
				// a part, so start with a line break.
			try {				
				getPart(partByteArrays, pkg, rp, r);
			} catch (Exception e) {
				throw new Docx4JException("Failed to add parts from relationships", e);
			}
		}
		
		
	}

	/**
	 * Get a Part (except a relationships part), and all its related parts.  
	 * This can be called directly from outside the library, in which case 
	 * the Part will not be owned by a Package until the calling code makes it so.  
	 * 
	 * @param zf
	 * @param source
	 * @param unusedZipEntries
	 * @param pkg
	 * @param r
	 * @param resolvedPartUri
	 * @throws Docx4JException
	 * @throws InvalidFormatException
	 */
	//private void getPart(ZipFile zf, Package pkg, RelationshipsPart rp, Relationship r)
	private void getPart(HashMap<String, ByteArray> partByteArrays, Package pkg, RelationshipsPart rp, Relationship r)
			throws Docx4JException, InvalidFormatException, URISyntaxException {
		
		Base source = null;
		String resolvedPartUri = null;
		
		if (r.getTargetMode() == null
				|| !r.getTargetMode().equals("External") ) {
			
			// Usual case
			
			source = rp.getSourceP();
			resolvedPartUri = URIHelper.resolvePartUri(rp.getSourceURI(), new URI(r.getTarget() ) ).toString();		

			// Now drop leading "/'
			resolvedPartUri = resolvedPartUri.substring(1);				

			// Now normalise it .. ie abc/def/../ghi
			// becomes abc/ghi
			// Maybe this isn't necessary with a zip file,
			// - ZipFile class may be smart enough to do it.
			// But it is certainly necessary in the JCR case.
//			resolvedPartUri = (new java.net.URI(resolvedPartUri)).normalize().toString();
//			log.info("Normalised, it is " + resolvedPartUri );				
			
		} else {			
			// EXTERNAL			
			if (loadExternalTargets && 
					r.getType().equals( Namespaces.IMAGE ) ) {
					// It could instead be, for example, of type hyperlink,
					// and we don't want to try to fetch that
				log.warn("Loading external resource " + r.getTarget() 
						   + " of type " + r.getType() );
				BinaryPart bp = getExternalResource(r.getTarget());
				pkg.getExternalResources().put(bp.getExternalTarget(), bp);			
			} else {				
				log.warn("Encountered (but not loading) external resource " + r.getTarget() 
						   + " of type " + r.getType() );				
			}						
			return;
		}
		
		String relationshipType = r.getType();		
			
		Part part = getRawPart(partByteArrays, ctm, resolvedPartUri);
		if (part instanceof BinaryPart
				|| part instanceof DefaultXmlPart) {
			// The constructors of other parts should take care of this...
			part.setRelationshipType(relationshipType);
		}
		rp.loadPart(part);

		// The source Part (or Package) might have a convenience
		// method for this
		if (source.setPartShortcut(part, relationshipType ) ) {
			log.info("Convenience method established from " + source.getPartName() 
					+ " to " + part.getPartName());
		}
		
		unusedZipEntries.put(resolvedPartUri, new Boolean(false));
		log.info(".. added." );
		
		RelationshipsPart rrp = getRelationshipsPart(partByteArrays, part);
		if (rrp!=null) {
			// recurse via this parts relationships, if it has any
			addPartsFromRelationships(partByteArrays, part, rrp );
			String relPart = PartName.getRelationshipsPartName(
					part.getPartName().getName().substring(1) );
			unusedZipEntries.put(relPart, new Boolean(false));					
		}
	}

	/**
	 * Get the Relationships Part (if there is one) for a given Part.  
	 * Otherwise return null.
	 * 
	 * @param zf
	 * @param part
	 * @return
	 * @throws InvalidFormatException
	 */
	//public RelationshipsPart getRelationshipsPart(ZipFile zf, Part part)
	public RelationshipsPart getRelationshipsPart(HashMap<String, ByteArray> partByteArrays, 
			Part part)
	throws Docx4JException, InvalidFormatException {
		RelationshipsPart rrp = null;
		// recurse via this parts relationships, if it has any
		//String relPart = PartName.getRelationshipsPartName(target);
		String relPart = PartName.getRelationshipsPartName(
				part.getPartName().getName().substring(1) );
		
		if (partByteArrays.get(relPart) !=null ) {
			log.info("Found relationships " + relPart );
			log.info("Recursing ... " );
			rrp = getRelationshipsPartFromZip(part,  partByteArrays,  relPart);
			part.setRelationships(rrp);
		} else {
			log.info("No relationships " + relPart );	
			return null;
		}
		return rrp;
	}
	
	

	/**
	 * Get a Part (except a relationships part), but not its relationships part
	 * or related parts.  Useful if you need quick access to just this part.
	 * This can be called directly from outside the library, in which case 
	 * the Part will not be owned by a Package until the calling code makes it so.  
	 * @see  To get a Part and all its related parts, and add all to a package, use
	 * getPart.
	 * @param zf
	 * @param resolvedPartUri
	 * @return
	 * @throws URISyntaxException
	 * @throws InvalidFormatException
	 */
	//public static Part getRawPart(ZipFile zf, ContentTypeManager ctm, String resolvedPartUri)
	public static Part getRawPart(HashMap<String, ByteArray> partByteArrays,
			ContentTypeManager ctm, String resolvedPartUri)	
			throws Docx4JException {
		Part part = null;
		
		InputStream is = null;
		try {
			try {
				log.debug("resolved uri: " + resolvedPartUri);
				is = getInputStreamFromZippedPart( partByteArrays,  resolvedPartUri);
				
				// Get a subclass of Part appropriate for this content type	
				// This will throw UnrecognisedPartException in the absence of
				// specific knowledge. Hence it is important to get the is
				// first, as we do above.
				part = ctm.getPart("/" + resolvedPartUri);				

				if (part instanceof org.docx4j.openpackaging.parts.ThemePart) {

					((org.docx4j.openpackaging.parts.JaxbXmlPart)part).setJAXBContext(Context.jcThemePart);
					((org.docx4j.openpackaging.parts.JaxbXmlPart)part).unmarshal( is );
					
				} else if (part instanceof org.docx4j.openpackaging.parts.DocPropsCorePart ) {

						((org.docx4j.openpackaging.parts.JaxbXmlPart)part).setJAXBContext(Context.jcDocPropsCore);
						((org.docx4j.openpackaging.parts.JaxbXmlPart)part).unmarshal( is );
						
				} else if (part instanceof org.docx4j.openpackaging.parts.DocPropsCustomPart ) {

						((org.docx4j.openpackaging.parts.JaxbXmlPart)part).setJAXBContext(Context.jcDocPropsCustom);
						((org.docx4j.openpackaging.parts.JaxbXmlPart)part).unmarshal( is );
						
				} else if (part instanceof org.docx4j.openpackaging.parts.DocPropsExtendedPart ) {

						((org.docx4j.openpackaging.parts.JaxbXmlPart)part).setJAXBContext(Context.jcDocPropsExtended);
						((org.docx4j.openpackaging.parts.JaxbXmlPart)part).unmarshal( is );
					
				} else if (part instanceof org.docx4j.openpackaging.parts.CustomXmlDataStoragePropertiesPart ) {

					((org.docx4j.openpackaging.parts.JaxbXmlPart)part).setJAXBContext(Context.jcCustomXmlProperties);
					((org.docx4j.openpackaging.parts.JaxbXmlPart)part).unmarshal( is );
						
				} else if (part instanceof org.docx4j.openpackaging.parts.JaxbXmlPart) {

					// MainDocument part, Styles part, Font part etc
					
					((org.docx4j.openpackaging.parts.JaxbXmlPart)part).setJAXBContext(Context.jc);
					((org.docx4j.openpackaging.parts.JaxbXmlPart)part).unmarshal( is );
					
				} else if (part instanceof org.docx4j.openpackaging.parts.Dom4jXmlPart) {
					
					((org.docx4j.openpackaging.parts.Dom4jXmlPart)part).setDocument( is );

				} else if (part instanceof org.docx4j.openpackaging.parts.WordprocessingML.BinaryPart) {
					
					log.debug("Detected BinaryPart " + part.getClass().getName() );
					((BinaryPart)part).setBinaryData(is);

				} else if (part instanceof org.docx4j.openpackaging.parts.CustomXmlDataStoragePart ) {
					
					CustomXmlDataStorage data = getCustomXmlDataStorageClass().factory();					
					data.setDocument(is); // Not necessarily JAXB, that's just our method name
					((org.docx4j.openpackaging.parts.CustomXmlDataStoragePart)part).setData(data);
					
				} else {
					// Shouldn't happen, since ContentTypeManagerImpl should
					// return an instance of one of the above, or throw an
					// Exception.
					
					log.error("No suitable part found for: " + resolvedPartUri);
					part = null;					
				}
			
			} catch (PartUnrecognisedException e) {
				log.warn("PartUnrecognisedException shouldn't happen anymore!");
				// Try to get it as a binary part
				part = getBinaryPart(partByteArrays, ctm, resolvedPartUri);
				((BinaryPart)part).setBinaryData(is);
			}
		} catch (Exception ex) {
			// IOException, URISyntaxException
			ex.printStackTrace();
			throw new Docx4JException("Failed to getPart", ex);			
			
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException exc) {
					exc.printStackTrace();
				}
			}
		}
		return part;
	}
	
	//public static Part getBinaryPart(ZipFile zf, ContentTypeManager ctm, String resolvedPartUri)
	public static Part getBinaryPart(HashMap<String, ByteArray> partByteArrays, 
			ContentTypeManager ctm, String resolvedPartUri)	
			throws Docx4JException {

		Part part = null;
		InputStream in = null;					
		try {			
			//in = zf.getInputStream( zf.getEntry(resolvedPartUri ) );
			in = partByteArrays.get(resolvedPartUri).getInputStream();
			part = new BinaryPart( new PartName("/" + resolvedPartUri));
			
			// Set content type
			part.setContentType(
					new ContentType(
							ctm.getContentType(new PartName("/" + resolvedPartUri)) ) );
			
			((BinaryPart)part).setBinaryData(in);
			log.info("Stored as BinaryData" );
			
		} catch (Exception ioe) {
			ioe.printStackTrace() ;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException exc) {
					exc.printStackTrace();
				}
			}
		}
		return part;
	}	
	
	private void dumpZipFileContents(ZipFile zf) {
		Enumeration entries = zf.entries();
		// Enumerate through the Zip entries until we find the one named
		// '[Content_Types].xml'.
		while (entries.hasMoreElements()) {
			ZipEntry entry = (ZipEntry) entries.nextElement();
			log.info( "\n\n" + entry.getName() + "\n" );
			InputStream in = null;
			try {			
				in = zf.getInputStream(entry);
			} catch (IOException e) {
				e.printStackTrace() ;
			}				
			SAXReader xmlReader = new SAXReader();
			Document xmlDoc = null;
			try {
				xmlDoc = xmlReader.read(in);
			} catch (DocumentException e) {
				// Will land here for binary files eg gif file
				e.printStackTrace() ;
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (IOException exc) {
						exc.printStackTrace();
					}
				}
			}
			debugPrint(xmlDoc);
			
		}
		
	}
	
	class ByteArray {
		
		byte[] bytes;
		
		ByteArray(byte[] bytes) {
			this.bytes = bytes;
			//log.info("Added " + bytes.length  );
		}
		
		InputStream getInputStream() {
			
			return new ByteArrayInputStream(bytes);
			
		}
		
	}
	
	
	
}