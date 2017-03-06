package org.mustangproject.ZUGFeRD;
/**
 * Mustangproject's ZUGFeRD implementation
 * ZUGFeRD importer
 * Licensed under the APLv2
 * @date 2014-07-07
 * @version 1.1.0
 * @author jstaerk
 * */

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;


//root.setNamespace(Namespace.getNamespace("http://www.energystar.gov/manageBldgs/req"));

public class ZUGFeRDImporter {


	private final static Logger LOG = LogManager.getLogger();

	/*
	 * call extract(importFilename). containsMeta() will return if ZUGFeRD data has been found, afterwards you can call getBIC(), getIBAN() etc.
	 */

	/** @var if metadata has been found */
	private boolean containsMeta = false;
	/** @var the reference (i.e. invoice number) of the sender */
	private String foreignReference;
	private String bic;
	private String iban;
	private String holder;
	private String amount;
	private String dueDate;
	/** Raw XML form of the extracted data - may be directly obtained. */
	private byte[] rawXML = null;
	private String bankName;
	private boolean amountFound;
	private boolean extracted = false;
	private boolean parsed = false;


	/**
	 * Extracts a ZUGFeRD invoice from a PDF document represented by a file name. Errors are just logged to STDOUT.
	 *
	 * @param pdfFilename
	 */
	public void extract(String pdfFilename) {
		try {
			extractLowLevel(new BufferedInputStream(new FileInputStream(pdfFilename)));
		} catch (IOException ioe) {
			LOG.error("Error extract PDF file", ioe);
		}
	}


	/**
	 * Extracts a ZUGFeRD invoice from a PDF document represented by an input stream. Errors are reported via exception handling.
	 *
	 * @param pdfStream
	 * @throws IOException
	 */
	public void extractLowLevel(InputStream pdfStream) throws IOException {
		try (PDDocument doc = PDDocument.load(pdfStream)) {
			PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
			PDEmbeddedFilesNameTreeNode etn;
			etn = names.getEmbeddedFiles();
			if (etn == null) {
				doc.close();
				return;
			}
			Map<String, PDComplexFileSpecification> efMap = etn.getNames();
			// String filePath = "/tmp/";
			for (String filename : efMap.keySet()) {
				/**
				 * currently (in the release candidate of version 1) only one attached file with the name ZUGFeRD-invoice.xml is allowed
				 */
				if (("ZUGFeRD-invoice.xml").equals(filename)) { //$NON-NLS-1$
					containsMeta = true;

					PDComplexFileSpecification fileSpec = efMap.get(filename);
					PDEmbeddedFile embeddedFile = fileSpec.getEmbeddedFile();
					// String embeddedFilename = filePath + filename;
					// File file = new File(filePath + filename);
					// System.out.println("Writing " + embeddedFilename);
					// ByteArrayOutputStream fileBytes=new
					// ByteArrayOutputStream();
					// FileOutputStream fos = new FileOutputStream(file);
					rawXML = embeddedFile.toByteArray();
					setMeta(new String(rawXML));
					extracted = true;
					// fos.write(embeddedFile.getByteArray());
					// fos.close();
				}
			}
		}
	}


	/**
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws SAXException
	 */
	public void parse() throws ParserConfigurationException, IOException, SAXException {
		DocumentBuilderFactory factory = null;
		DocumentBuilder builder = null;
		Document document = null;

		if (!extracted) {
			throw new RuntimeException("extract() or extractLowLevel() must be used before parsing.");
		}

		factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true); // otherwise we can not act namespace independend, i.e. use document.getElementsByTagNameNS("*",...
		builder = factory.newDocumentBuilder();
		try (InputStream bais = new ByteArrayInputStream(rawXML)) {
			document = builder.parse(bais);
		}

		NodeList ndList;
		// rootNode = document.getDocumentElement();
		// ApplicableSupplyChainTradeSettlement
		ndList = document.getDocumentElement().getElementsByTagNameNS("*", "PaymentReference"); //$NON-NLS-1$
		for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
			Node booking = ndList.item(bookingIndex);
			// if there is a attribute in the tag number:value
			setForeignReference(booking.getTextContent());
		}
		/*
		 * ndList = document .getElementsByTagName("GermanBankleitzahlID"); //$NON-NLS-1$ for (int bookingIndex = 0; bookingIndex < ndList .getLength();
		 * bookingIndex++) { Node booking = ndList.item(bookingIndex); // if there is a attribute in the tag number:value setBIC(booking.getTextContent()); }
		 * ndList = document.getElementsByTagName("ProprietaryID"); //$NON-NLS-1$ for (int bookingIndex = 0; bookingIndex < ndList .getLength(); bookingIndex++)
		 * { Node booking = ndList.item(bookingIndex); // if there is a attribute in the tag number:value setIBAN(booking.getTextContent()); }
		 * <ram:PayeePartyCreditorFinancialAccount> <ram:IBANID>DE1234</ram:IBANID> </ram:PayeePartyCreditorFinancialAccount>
		 * <ram:PayeeSpecifiedCreditorFinancialInstitution> <ram:BICID>DE5656565</ram:BICID> <ram:Name>Commerzbank</ram:Name>
		 * </ram:PayeeSpecifiedCreditorFinancialInstitution>
		 */
		ndList = document.getElementsByTagNameNS("*", "PayeePartyCreditorFinancialAccount"); //$NON-NLS-1$

		for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
			Node booking = ndList.item(bookingIndex);
			// there are many "name" elements, so get the one below
			// SellerTradeParty
			NodeList bookingDetails = booking.getChildNodes();
			for (int detailIndex = 0; detailIndex < bookingDetails.getLength(); detailIndex++) {
				Node detail = bookingDetails.item(detailIndex);
				if ((detail.getLocalName() != null) && (detail.getLocalName().equals("IBANID"))) { //$NON-NLS-1$
					setIBAN(detail.getTextContent());
				}
			}
		}

		ndList = document.getElementsByTagNameNS("*", "PayeeSpecifiedCreditorFinancialInstitution"); //$NON-NLS-1$
		for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
			Node booking = ndList.item(bookingIndex);
			// there are many "name" elements, so get the one below
			// SellerTradeParty
			NodeList bookingDetails = booking.getChildNodes();
			for (int detailIndex = 0; detailIndex < bookingDetails.getLength(); detailIndex++) {
				Node detail = bookingDetails.item(detailIndex);
				if ((detail.getLocalName() != null) && (detail.getLocalName().equals("BICID"))) { //$NON-NLS-1$
					setBIC(detail.getTextContent());
				}
				if ((detail.getLocalName() != null) && (detail.getLocalName().equals("Name"))) { //$NON-NLS-1$
					setBankName(detail.getTextContent());
				}
			}
		}

		//
		ndList = document.getElementsByTagNameNS("*", "SellerTradeParty"); //$NON-NLS-1$

		for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
			Node booking = ndList.item(bookingIndex);
			// there are many "name" elements, so get the one below
			// SellerTradeParty
			NodeList bookingDetails = booking.getChildNodes();
			for (int detailIndex = 0; detailIndex < bookingDetails
					.getLength(); detailIndex++) {
				Node detail = bookingDetails.item(detailIndex);
				if ((detail.getLocalName() != null) && (detail.getLocalName().equals("Name"))) { //$NON-NLS-1$
					setHolder(detail.getTextContent());
				}
			}
		}

		ndList = document.getElementsByTagNameNS("*", "DuePayableAmount"); //$NON-NLS-1$
		for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
			Node booking = ndList.item(bookingIndex);
			// if there is a attribute in the tag number:value
			amountFound = true;
			setAmount(booking.getTextContent());
		}


		if (!amountFound) {
			/*
			 * there is apparently no requirement to mention DuePayableAmount,, if it's not there, check for GrandTotalAmount
			 */
			ndList = document.getElementsByTagNameNS("*", "GrandTotalAmount"); //$NON-NLS-1$
			for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
				Node booking = ndList.item(bookingIndex);
				// if there is a attribute in the tag number:value
				amountFound = true;
				setAmount(booking.getTextContent());
			}
		}

		ndList = document.getElementsByTagNameNS("*", "SpecifiedTradePaymentTerms"); //$NON-NLS-1$

		for (int bookingIndex = 0; bookingIndex < ndList.getLength(); bookingIndex++) {
			Node booking = ndList.item(bookingIndex);
			// there are many "name" elements, so get the one below
			// SellerTradeParty
			NodeList bookingDetails = booking.getChildNodes();
			for (int detailIndex = 0; detailIndex < bookingDetails
					.getLength(); detailIndex++) {
				Node detail = bookingDetails.item(detailIndex);
				if ((detail.getLocalName() != null) && (detail.getLocalName().equals("DueDateDateTime"))) { //$NON-NLS-1$
					setDueDate(detail.getTextContent().trim());
				}
			}
		}
		parsed = true;
	}


	public boolean containsMeta() {
		return containsMeta;
	}

	private final String errmessage = "use parse() before requesting a value";


	public String getForeignReference() {
		if (!parsed) {
			throw new RuntimeException(errmessage);
		}
		return foreignReference;
	}


	private void setForeignReference(String foreignReference) {
		this.foreignReference = foreignReference;
	}


	public String getBIC() {
		if (!parsed) {
			throw new RuntimeException(errmessage);
		}
		return bic;
	}


	private void setBIC(String bic) {
		this.bic = bic;
	}


	private void setDueDate(String dueDate) {
		this.dueDate = dueDate;
	}


	private void setBankName(String bankname) {
		bankName = bankname;
	}


	public String getIBAN() {
		if (!parsed) {
			throw new RuntimeException(errmessage);
		}
		return iban;
	}


	public String getBankName() {
		if (!parsed) {
			throw new RuntimeException(errmessage);
		}
		return bankName;
	}


	private void setIBAN(String iban) {
		this.iban = iban;
	}


	public String getHolder() {
		if (rawXML == null) {
			throw new RuntimeException(errmessage);
		}
		return holder;
	}


	private void setHolder(String holder) {
		this.holder = holder;
	}


	public String getAmount() {
		if (rawXML == null) {
			throw new RuntimeException(errmessage);
		}
		return amount;
	}


	public String getDueDate() {
		if (rawXML == null) {
			throw new RuntimeException(errmessage);
		}
		return dueDate;
	}


	private void setAmount(String amount) {
		this.amount = amount;
	}


	public void setMeta(String meta) {
		rawXML = meta.getBytes();
	}


	public String getMeta() {
		if (rawXML == null) {
			return null;
		}

		return new String(rawXML);
	}


	/**
	 * Returns the raw XML data as extracted from the ZUGFeRD PDF file.
	 *
	 * @return raw XML data
	 */
	public byte[] getRawXML() {
		return rawXML;
	}


	/**
	 * will return true if the metadata (just extract-ed or set with setMeta) contains ZUGFeRD XML
	 *
	 * @return true, when parsing
	 */
	public boolean canParse() {
		// SpecifiedExchangedDocumentContext is in the schema, so a relatively good indication if zugferd is present - better than just invoice
		String meta = getMeta();
		return (meta != null) && (meta.length() > 0) && (meta.contains("SpecifiedExchangedDocumentContext")); //$NON-NLS-1$
	}
}
