package com.test;

import java.util.regex.PatternSyntaxException;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.JAXBContext;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FilesStats {

	// represents record for one file extension
	private static class TypeRecord {
		
		@XmlElement
		public String type;
		@XmlElement
		public int count = 0;
		@XmlElement
		public long size = 0;
		@XmlElement
		public long lines = 0;
		@XmlElement
		public long nonEmptyLines = 0;
		@XmlElement
		public long linesWithComments = 0;
		
	}
	
	// wrapper for serialization
	@XmlRootElement
	private static class RecordsList {
		
		@XmlElement(name = "typeRecord")
		public List<TypeRecord> records = new ArrayList<>();
		
	}
	
	// all data gathered from all 'walkers'
	private static Map<String, TypeRecord> globalRecordsList = new HashMap<>();
	
	// how many walkers have returned so far
	public static volatile int completed = 0;
	
	// method to submit local stats from 'walker' threads to global stats
	public static synchronized void submitRecords(Map<String, TypeRecord> records) {
		
		// iterating through each submitted type record
		for (String extension: records.keySet()) {
			TypeRecord submittedRecord = records.get(extension);
			
			// if there is no record for the given type in the global stats, just put it there
			if (!globalRecordsList.containsKey(extension)) {
				globalRecordsList.put(extension, submittedRecord);
				continue;
			}
			
			// if record for the type exists, add submitted stats to it
			TypeRecord globalRecord = globalRecordsList.get(extension);
			globalRecord.count += submittedRecord.count;
			globalRecord.lines += submittedRecord.lines;
			globalRecord.nonEmptyLines += submittedRecord.nonEmptyLines;
			globalRecord.linesWithComments += submittedRecord.linesWithComments;
			globalRecord.size += submittedRecord.size;
		}
	}
	
	public static String getExtension(String name) {
		name = name.toUpperCase();
		if (name.contains(".")) {
			return name.substring(name.lastIndexOf(".") + 1).toUpperCase();
		} else {
			return "";
		}
	}
	
	public static void main(String[] args) throws Exception {
		
		// declaring parameters
		Path path = null;
		String[] includeExtParam = new String[0];
		String[] excludeExtParam = new String[0];
		boolean recursive = false;
		int maxDepth = Integer.MAX_VALUE;
		int thread = 1;
		String output = "plain";
		
		// collecting parameters
		for (String arg: args) {
			if ("--recursive".equals(arg)) {
				recursive = true;
				continue;
			} else if (arg.startsWith("--max-depth=")) {
				maxDepth = Integer.parseInt(arg.substring(12, arg.length()));
                continue;
			} else if (arg.startsWith("--thread=")) {
				thread = Integer.parseInt(arg.substring(9, arg.length()));
                continue;
			} else if (arg.startsWith("--include-ext=")) {
                if (excludeExtParam.length > 0) {
                    System.out.println("Both include-ext and exclude-ext are specified, please pick one");
                    return;
                }
				arg = arg.toUpperCase();
				includeExtParam = arg.substring(14, arg.length()).split(",");
                continue;
			} else if (arg.startsWith("--exclude-ext=")) {
                if (includeExtParam.length > 0) {
                    System.out.println("Both include-ext and exclude-ext are specified, please pick one");
                    return;
                }
				arg = arg.toUpperCase();
				excludeExtParam = arg.substring(14, arg.length()).split(",");
                continue;
			} else if (arg.startsWith("--output=")) {
				output = arg.substring(9, arg.length());
				continue;
			} else {
                path = Paths.get(arg);
                continue;
			}
		}
		
        // check if we found root path
        if (path == null) {
            System.out.println("Path not specified");
            return;
        }
        
		// if not recursive, oblige regardless of max-depth parameter
		if (!recursive) {
			maxDepth = 1;
		}
		
        // getting all files needed inside the path
        List<Path> paths = new ArrayList<>();
		final String[] includeExt = includeExtParam;
		final String[] excludeExt = excludeExtParam;
        try (Stream<Path> allFiles = Files.walk(path, maxDepth)) {
            
            // filtering files by conditions
            allFiles.filter((Path onePath) -> {
                File file = onePath.toFile();
                if (file.isDirectory()) {
                    return false;
                }
                String name = file.getName();
				
				// obtaining extension
				String extension = getExtension(name);
				
				// checking if it matches include-ext or exclude-ext
				if (includeExt.length > 0) {
					for (String ext: includeExt) {
						if (ext.equals(extension)) {
							return true;
						}
					}
					return false;
				}
				if (excludeExt.length > 0) {
					for (String ext: excludeExt) {
						if (ext.endsWith(extension)) {
							return false;
						}
					}
				}
                return true;
            })
            
            // put the files in the list
            .forEach((Path file) -> {paths.add(file);});
            
        }
        
		// initialize walker threads
		Walker[] walkers = new Walker[thread];
		for (int a = 0; a < walkers.length; a++) {
			walkers[a] = new Walker();
		}
		
		// assign files to walkers as fairly as possible
		for (int a = 0; a < paths.size(); a++) {
			walkers[a % thread].filesToProcess.add(paths.get(a));
		}
		
		// launch walker threads
		for (Walker walker: walkers) {
			walker.start();
		}
		while (completed < thread) {
			// wait for all walkers to finish collecting stats
		}
		
		// prepare serializable object if needed
		RecordsList list = null;
		if (!output.equals("plain")) {
			list = new RecordsList();
			for (String extension: globalRecordsList.keySet()) {
				TypeRecord record = globalRecordsList.get(extension);
				list.records.add(record);
				record.type = extension;
			}
		}
		
		// print results
		if (output.equals("xml")) {
			File xml = path.resolve("result.xml").toFile();
			JAXBContext context = JAXBContext.newInstance(RecordsList.class);
			Marshaller marshaller = context.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
			marshaller.marshal(list, xml);
		} else if (output.equals("json")) {
			File json = path.resolve("result.json").toFile();
			ObjectMapper mapper = new ObjectMapper();
			mapper.writeValue(json, list);
		} else if (output.equals("plain")) {
			System.out.println("Stats for each file type:");
			for (String extension: globalRecordsList.keySet()) {
				TypeRecord record = globalRecordsList.get(extension);
				System.out.println("-------------------------");
				System.out.println("File type: " + extension);
				System.out.println("Files count: " + record.count);
				System.out.println("Files total size: " + record.size);
				System.out.println("Files total lines count: " + record.lines);
				System.out.println("Files total count of non-empty lines: " + record.nonEmptyLines);
				System.out.println("Files total count of lines with comments: " + record.linesWithComments);
			}
		}
	}
	
	// thread that walks through the files
	private static class Walker extends Thread {
		
		public ArrayList<Path> filesToProcess = new ArrayList<>();
		
		private HashMap<String, TypeRecord> records = new HashMap<>();
		
		@Override
		public void run() {
			
			// walking through each of assigned files
			for (Path path: filesToProcess) {
				File file = path.toFile();
				
				// opening the file
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
				
					// declaring stats vars for the files
					int lines = 0;
					int nonEmptyLines = 0;
					int linesWithComments = 0;
				
					String name = file.getName();
					System.out.println(name);
					String extension = getExtension(name);
					String line = reader.readLine();
					
					// iterating through each line
					while (line != null) {
						lines ++;
						if (!"".equals(line)) {
							nonEmptyLines ++;
						}
						if (("BASH".equals(extension) && line.indexOf("#") != -1)
							||
							("JAVA".equals(extension) && line.indexOf("//") != -1)) {
							linesWithComments ++;
						}
						line = reader.readLine();
					}
					
					// record to put the stats
					TypeRecord record;
					
					// creating new record for the extension if it doesn't exist
					if (!records.containsKey(extension)) {
						record = new TypeRecord();
						records.put(extension, record);
					} else {
						record = records.get(extension);
					}
					
					// adding file stats to the record
					record.count += 1;
					record.lines += lines;
					record.nonEmptyLines += nonEmptyLines;
					record.linesWithComments += linesWithComments;
					record.size += Files.readAttributes(path, BasicFileAttributes.class).size();
					
				} catch (Exception e) {
					e.printStackTrace(System.out);
					continue;
				}
				
				// going to the next path
			}
			
			// after we've walked through all assigned files, submit collected stats to the main thread
			submitRecords(records);
			completed += 1;
		}
		
	}

}