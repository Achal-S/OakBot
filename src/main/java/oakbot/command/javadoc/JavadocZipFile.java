package oakbot.command.javadoc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import oakbot.util.CloseableIterator;
import oakbot.util.XPathWrapper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Represents a Javadoc ZIP file that was generated by <a
 * href="https://github.com/mangstadt/oakbot-doclet">oakbot-doclet</a>.
 * @author Michael Angstadt
 */
public class JavadocZipFile {
	private static final String extension = ".xml";
	private static final String infoFileName = "info" + extension;

	/**
	 * Matches placeholders in the javadocUrlPattern field.
	 */
	private final Pattern javadocUrlPatternFieldRegex = Pattern.compile("\\{(.*?)(\\s+(.*?))?\\}");

	/**
	 * The file system path to the ZIP file.
	 */
	private final Path file;

	/**
	 * The base URL for the project's online Javadoc page (e.g.
	 * "http://www.example.com/javadocs/")
	 */
	private final String baseUrl;

	/**
	 * The project's name (e.g. "ez-vcard").
	 */
	private final String name;

	/**
	 * The version of the project that this Javadoc information is from.
	 */
	private final String version;

	/**
	 * The URL to the project's webpage.
	 */
	private final String projectUrl;

	/**
	 * Defines how the URL for a particular class's Javadoc page should be
	 * constructed.
	 */
	private final String javadocUrlPattern;

	/**
	 * @param file the ZIP file
	 * @throws IOException if there's a problem reading the metadata from the
	 * file
	 */
	public JavadocZipFile(Path file) throws IOException {
		this.file = file.toRealPath();

		Document document;
		try (FileSystem fs = open()) {
			Path info = fs.getPath("/" + infoFileName);
			if (!Files.exists(info)) {
				baseUrl = name = version = projectUrl = javadocUrlPattern = null;
				return;
			}

			document = parseXml(info);
		}

		XPathWrapper xpath = new XPathWrapper();
		Element infoElement = xpath.element("/info", document);
		if (infoElement == null) {
			baseUrl = name = version = projectUrl = javadocUrlPattern = null;
			return;
		}

		String name = infoElement.getAttribute("name");
		this.name = name.isEmpty() ? null : name;

		String baseUrl = infoElement.getAttribute("baseUrl");
		if (baseUrl.isEmpty()) {
			this.baseUrl = null;
		} else {
			//make sure the base URL ends with a "/"
			this.baseUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/");
		}

		String projectUrl = infoElement.getAttribute("projectUrl");
		this.projectUrl = projectUrl.isEmpty() ? null : projectUrl;

		String version = infoElement.getAttribute("version");
		this.version = version.isEmpty() ? null : version;

		String javadocUrlPattern = infoElement.getAttribute("javadocUrlPattern");
		this.javadocUrlPattern = javadocUrlPattern.isEmpty() ? null : javadocUrlPattern;
	}

	/**
	 * Gets the URL to a class's Javadoc page.
	 * @param info the class
	 * @param frames true to get the URL to the version of the web page with
	 * frames, false not to
	 * @return the URL or null if no base URL was defined in this ZIP file
	 */
	public String getUrl(ClassInfo info, boolean frames) {
		if (javadocUrlPattern != null) {
			return javadocUrlPattern(info);
		}

		if (baseUrl == null) {
			return null;
		}

		//@formatter:off
		return frames ?
		baseUrl + "index.html?" + info.getName().getFullyQualified().replace('.', '/') + ".html" :
		baseUrl + info.getName().getFullyQualified().replace('.', '/') + ".html";
		//@formatter:on
	}

	/**
	 * Creates a URL to a class's Javadoc page using the javadoc URL pattern.
	 * @param info the class info
	 * @return the URL
	 */
	private String javadocUrlPattern(ClassInfo info) {
		Matcher m = javadocUrlPatternFieldRegex.matcher(javadocUrlPattern);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String field = m.group(1);
			String replacement;
			switch (field) {
			case "baseUrl":
				replacement = baseUrl;
				break;
			case "full":
				replacement = info.getName().getFullyQualified();
				String delimitor = m.group(3);
				if (delimitor != null) {
					replacement = replacement.replace(".", delimitor);
				}
				break;
			default:
				replacement = "";
				break;
			}

			m.appendReplacement(sb, replacement);
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Gets a list of all classes that are in the library.
	 * @return the fully-qualified names of all the classes
	 * @throws IOException if there's a problem reading the ZIP file
	 */
	public CloseableIterator<ClassName> getClasses() throws IOException {
		/*
		 * TODO Support XML files that are nested in directory trees instead of
		 * all the files being in the root.
		 * 
		 * For example, instead of "/java.lang.String.xml", support
		 * "/java/lang/String.xml"
		 */
		final FileSystem fs = open();
		final DirectoryStream<Path> stream = Files.newDirectoryStream(fs.getPath("/"), entry -> {
			String name = entry.getFileName().toString();
			if (!name.endsWith(extension)) {
				return false;
			}

			return !name.equals(infoFileName);
		});

		final Iterator<Path> it = stream.iterator();
		return new CloseableIterator<ClassName>() {
			@Override
			public boolean hasNext() {
				return it.hasNext();
			}

			@Override
			public ClassName next() {
				Path file = it.next();
				return toClassName(file);
			}

			@Override
			public void close() throws IOException {
				try {
					stream.close();
				} catch (IOException e) {
					//ignore
				}
				fs.close();
			}

			private ClassName toClassName(Path file) {
				String fileName = file.getFileName().toString();
				String fullName = fileName.substring(0, fileName.length() - extension.length());
				return new ClassName(fullName);
			}
		};
	}

	/**
	 * Gets information about a class.
	 * @param fullName the fully-qualified class name (e.g. "java.lang.String")
	 * @return the class info or null if the class was not found
	 * @throws IOException if there was a problem reading from the ZIP file or
	 * parsing the XML
	 */
	public ClassInfo getClassInfo(String fullName) throws IOException {
		Document document;
		try (FileSystem fs = open()) {
			Path path = fs.getPath(fullName + extension);
			if (!Files.exists(path)) {
				path = fs.getPath(fullName.replace('.', '/') + extension);
				if (!Files.exists(path)) {
					return null;
				}
			}

			document = parseXml(path);
		}

		return ClassInfoXmlParser.parse(document, this);
	}

	/**
	 * Gets the base URL of this library's Javadocs.
	 * @return the base URL or null if none was defined
	 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * Gets the name of this library.
	 * @return the name (e.g. "jsoup") or null if none was defined
	 */
	public String getName() {
		return name;
	}

	/**
	 * Gets the version number of this library.
	 * @return the version number (e.g. "1.8.1") or null if none was defined
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Gets the URL to the library's webpage.
	 * @return the URL or null if none was defined
	 */
	public String getProjectUrl() {
		return projectUrl;
	}

	/**
	 * Get the path to the ZIP file.
	 * @return the path to the ZIP file
	 */
	public Path getPath() {
		return file;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + file.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		JavadocZipFile other = (JavadocZipFile) obj;
		if (!file.equals(other.file)) return false;
		return true;
	}

	/**
	 * Parses an XML file.
	 * @param path the path to the file
	 * @return the DOM tree
	 * @throws IOException if there's a problem opening or parsing the file
	 */
	private static Document parseXml(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
		} catch (SAXException e) {
			throw new IOException(e);
		} catch (ParserConfigurationException e) {
			//should never be thrown because we are not doing any configurations with the parser
			throw new RuntimeException(e);
		}
	}

	/**
	 * Opens the ZIP file.
	 * @return the file system handle
	 * @throws IOException if there's a problem opening the ZIP file
	 */
	private FileSystem open() throws IOException {
		return FileSystems.newFileSystem(file, null);
	}
}