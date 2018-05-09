package org.daisy.dotify.tasks.impl.input.xml;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.daisy.dotify.common.io.ResourceLocator;
import org.daisy.dotify.common.io.ResourceLocatorException;
import org.daisy.dotify.tasks.impl.identity.XmlIdentifier;
import org.daisy.dotify.tasks.impl.input.DuplicatorTask;
import org.daisy.dotify.tasks.impl.input.ValidatorTask;
import org.daisy.dotify.tasks.tools.XsltTask;
import org.daisy.streamline.api.identity.IdentificationFailedException;
import org.daisy.streamline.api.media.AnnotatedFile;
import org.daisy.streamline.api.media.DefaultAnnotatedFile;
import org.daisy.streamline.api.option.UserOption;
import org.daisy.streamline.api.tasks.ExpandingTask;
import org.daisy.streamline.api.tasks.InternalTask;
import org.daisy.streamline.api.tasks.InternalTaskException;
import org.daisy.streamline.api.tasks.TaskGroup;
import org.daisy.streamline.api.tasks.TaskSystemException;

/**
 * <p>Provides a method to determine the input format and load the 
 * appropriate settings based on the detected input format.</p>
 * 
 * <p>The InputDetectorTaskSystem is specifically designed to aid 
 * the process of selecting and executing the correct validation rules 
 * and transformation for a given input document and locale.</p>
 * 
 * <p>Note that, input format must be well-formed XML.</p>
 * 
 * <p>Resources are located in the following order:</p>
 * <ul> 
 * <li>localBase/[output format]/[input format].properties</li>
 * <li>localBase/[output format]/xml.properties</li>
 * <li>commonBase/[output format]/[input format].properties</li>
 * <li>commonBase/[output format]/xml.properties</li>
 * </ul>
 * <p>The properties file for the format should contain two entries:</p>
 * <ul>
 * <li>&lt;entry key="validation"&gt;path/to/schema/file&lt;/entry&gt;</li>
 * <li>&lt;entry key="transformation"&gt;path/to/xslt/file&lt;/entry&gt;</li>
 * </ul>
 * <p>Paths in the properties file are relative to the resource base url.</p>
 * <p>Whitespace normalization of the OBFL file is added last in the chain.</p>
 * 
 * @author Joel Håkansson
 *
 */
public class XMLInputManager implements TaskGroup {
	/**
	 * Specifies a location where the intermediary obfl output should be stored
	 */
	static final String OBFL_OUTPUT_LOCATION = "obfl-output-location";
	private static final String TEMPLATE_KEY = "template";
	private static final String TEMPLATES_PATH = "templates/";
	private static final String LOCALIZATION_PROPS = "localization.xml";
	private final ResourceLocator localLocator;
	private final ResourceLocator commonLocator;
	private final String name;
	private final Logger logger;

	/**
	 * Creates a new xml input manager with the specified options. 
	 * @param localLocator a locator for local resources
	 * @param commonLocator a locator for common resources
	 */
	public XMLInputManager(ResourceLocator localLocator, ResourceLocator commonLocator) {
		this(localLocator, commonLocator, "XMLInputManager");
	}
	
	/**
	 * Creates a new xml input manager with the specified options. 
	 * @param localLocator a locator for local resources
	 * @param commonLocator a locator for common resources
	 * @param name a name for the task group
	 */
	public XMLInputManager(ResourceLocator localLocator, ResourceLocator commonLocator, String name) {
		this.localLocator = localLocator;
		this.commonLocator = commonLocator;
		this.name = name;
		this.logger = Logger.getLogger(XMLInputManager.class.getCanonicalName());
	}
	
	@Override
	public String getName() {
		return name;
	}

	@Override
	public List<InternalTask> compile(Map<String, Object> parameters)
			throws TaskSystemException {
		String template;
		if (parameters.get(TEMPLATE_KEY)==null) {
			logger.info("No template set, using default.");
			template = "default";
		} else {
			template = parameters.get(TEMPLATE_KEY).toString().toLowerCase();
		}
		
		List<InternalTask> ret = new ArrayList<>();
		ret.add(new XMLExpandingTask(template, makeXSLTParams(parameters)));

		String keep = (String)parameters.get(OBFL_OUTPUT_LOCATION);
		if (keep!=null && !"".equals(keep)) {
			ret.add(new DuplicatorTask("OBFL archiver", new File(keep)));
		}
		return ret;
	}
	
	private Map<String, Object> makeXSLTParams(Map<String, Object> parameters) {
		Map<String, Object> xsltParams = new HashMap<>();
		{
			Properties p2 = new Properties();
			try {
				p2.loadFromXML(localLocator.getResource(LOCALIZATION_PROPS).openStream());
			} catch (InvalidPropertiesFormatException e) {
				logger.log(Level.FINE, "", e);
			} catch (ResourceLocatorException e) {
				logger.log(Level.FINE, "", e);
			} catch (IOException e) {
				logger.log(Level.FINE, "", e);
			}
			
			for (Object key3 : p2.keySet()) {
				xsltParams.put(key3.toString(), p2.get(key3).toString());
			}
		}
		for (String key2 : parameters.keySet()) {
			xsltParams.put(key2, parameters.get(key2));
		}

		return Collections.unmodifiableMap(xsltParams);
	}
	
	private class XMLExpandingTask extends ExpandingTask {
		private final String template;
		private final Map<String, Object> xsltParams;
		
		XMLExpandingTask(String template, Map<String, Object> xsltParams) {
			super("XML Tasks Bundle");
			this.template = template;
			this.xsltParams = xsltParams;
		}

		@Override
		public List<InternalTask> resolve(AnnotatedFile input) throws InternalTaskException {
			String inputformat = null;
			String rootElement = null;
			try {
				if (!input.getProperties().containsKey(XmlIdentifier.LOCAL_NAME_KEY) || !input.getProperties().containsKey(XmlIdentifier.XMLNS_KEY)) {
					 input = new XmlIdentifier().identify(input);
				}
				String rootNS = String.valueOf(input.getProperties().get(XmlIdentifier.XMLNS_KEY));
				rootElement = String.valueOf(input.getProperties().get(XmlIdentifier.LOCAL_NAME_KEY));
				DefaultInputUrlResourceLocator p = DefaultInputUrlResourceLocator.getInstance();

				inputformat = p.getConfigFileName(rootElement, rootNS);
				if (inputformat !=null && "".equals(inputformat)) {
					return new ArrayList<>();
				}
			} catch (IdentificationFailedException e) {
				throw new InternalTaskException("Failed to read input as xml", e);
			} catch (IOException e) {
				throw new InternalTaskException("IOException while reading input", e);
			}
			
			String xmlformat = "xml.properties";
			String basePath = TEMPLATES_PATH + template + "/";
			if (inputformat!=null) {
				try {
					return readConfiguration(rootElement, localLocator, basePath + inputformat);
				} catch (ResourceLocatorException e) {
					logger.fine("Cannot find localized URL " + basePath + inputformat);
				}
			}
			try {
				return readConfiguration(rootElement, localLocator, basePath + xmlformat);
			} catch (ResourceLocatorException e) {
				logger.fine("Cannot find localized URL " + basePath + xmlformat);
			}
			if (inputformat!=null) {
				try {
					return readConfiguration(rootElement, commonLocator, basePath + inputformat);
				} catch (ResourceLocatorException e) {
					logger.fine("Cannot find common URL " + basePath + inputformat);
				}
			}
			try {
				return readConfiguration(rootElement, commonLocator, basePath + xmlformat);
			} catch (ResourceLocatorException e) {
				logger.fine("Cannot find common URL " + basePath + xmlformat);
			}
			throw new InternalTaskException("Unable to open a configuration stream for the format.");
		}
		
		private List<InternalTask> readConfiguration(String type, ResourceLocator locator, String path) throws InternalTaskException, ResourceLocatorException {
			URL t = locator.getResource(path);
			List<InternalTask> setup = new ArrayList<>();				
			try {
				Properties pa = new Properties();
				try {
					logger.fine("Opening stream: " + t.getFile());					
					pa.loadFromXML(t.openStream());
				} catch (IOException e) {
					logger.log(Level.FINE, "Cannot open stream: " + t.getFile(), e);
					throw new ResourceLocatorException("Cannot open stream");
				}
				addValidationTask(type, removeSchemas(pa, "validation"), setup, locator);
				addXsltTask(type, removeSchemas(pa, "transformation"), setup, locator); 
				for (Object key : pa.keySet()) {
					logger.info("Unrecognized key: " + key);							
				}
			} catch (IOException e) {
				throw new InternalTaskException("Unable to open settings file.", e);
			}
			
			return setup;
		}

		@Override
		@Deprecated
		public List<InternalTask> resolve(File input) throws InternalTaskException {
			return resolve(new DefaultAnnotatedFile.Builder(input).build());
		}
		
		private void addValidationTask(String type, String[] schemas, List<InternalTask> setup, ResourceLocator locator) throws ResourceLocatorException {
			if (schemas!=null) {
				for (String s : schemas) {
					if (s!=null && !s.equals("")) {
						setup.add(new ValidatorTask(type + " conformance checker: " + s, locator.getResource(s)));
					}
				}
			} 
		}
		
		private void addXsltTask(String type, String[] schemas, List<InternalTask> setup, ResourceLocator locator) throws ResourceLocatorException {
			if (schemas!=null) {
				for (String s : schemas) {
					if (s!=null && s!="") {
						setup.add(new XsltTask(type + " to OBFL converter", locator.getResource(s), xsltParams));
					}
				}
			}
		}
		private String[] removeSchemas(Properties p, String key) {
			Object o = p.remove(key);
			String value = (o instanceof String) ? (String)o : null;
			if (value==null) {
				return null;
			} else {
				return value.split("\\s*,\\s*");
			}
		}
	}

	@Override
	public List<UserOption> getOptions() {
		List<UserOption> ret = new ArrayList<>();
		ret.add(new UserOption.Builder(OBFL_OUTPUT_LOCATION).description("Path to store intermediary OBFL-file.").build());
		return ret;
	}

}
