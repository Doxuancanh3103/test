package kwmc.tool;

import com.ibm.icu.text.CharsetDetector;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipException;

public class ConvertController {

    @FXML
    private Button convertBtn;

    @FXML
    private Button chooseFileBtn;

    @FXML
    private TextField fileNameTextField;

    @FXML
    private ProgressBar progressBar;

    private File file;

    private File fileSave;

    private Map<String,String> fileMap;

    private int numberOfFile;

    List<Future<?>> futureFiles;

    final String[] searchElements = {"From:","To:","CC:","BCC:","Subject:"};

    ExecutorService executorFile = Executors.newFixedThreadPool(100);
    ExecutorService executorLine = Executors.newFixedThreadPool(100);

    @FXML
    protected void chooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("*.zip", "*.zip"));
        file = fileChooser.showOpenDialog(chooseFileBtn.getScene().getWindow());
        if (file != null) {
            fileNameTextField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    protected void convert() {
        if (file == null){
            showAlert("Please choose a file");
            return;
        }
        String[] fileSplit = file.getName().split("\\.");
        if (!"zip".equalsIgnoreCase(fileSplit[fileSplit.length-1])){
            showAlert("File must be zip file");
            return;
        }


        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save");
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("*.zip", "*.zip"));
        File selectedFile = fileChooser.showSaveDialog(convertBtn.getScene().getWindow());
        if (selectedFile != null) {
            fileSave = selectedFile;
            convertBeckyToKwmc(file);
        }
    }

    private void showAlert(String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Convert Error");
        alert.setContentText(text);
        alert.showAndWait();
    }

    private void showAlertSuccess(){
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Information");
        alert.setHeaderText("Convert success");
        alert.show();
    }

    private void convertBeckyToKwmc(File file) {
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
            bufferedInputStream.mark(fileInputStream.available());
            ZipArchiveInputStream zipArchiveInputStream = new ZipArchiveInputStream(bufferedInputStream, "MS932");
            Map<String, byte[]> zipArchiveEntryMap = getZipArchiveEntry(zipArchiveInputStream);
            this.numberOfFile = zipArchiveEntryMap.size();
            processConvert(zipArchiveEntryMap);


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, byte[]> getZipArchiveEntry(ZipArchiveInputStream zipInputStream) throws IOException {
        Map<String, byte[]> zipArchiveEntryMap = new LinkedHashMap<>();

        try {
            ZipArchiveEntry entry;

            while ((entry = zipInputStream.getNextZipEntry()) != null) {
                zipArchiveEntryMap.put(entry.getName(), IOUtils.toByteArray(zipInputStream));
            }
        } catch (Exception e) {
            throw new ZipException("Unexpected record signature");
        }

        zipInputStream.close();

        return zipArchiveEntryMap;
    }

    private void processConvert(Map<String, byte[]> zipArchiveEntryMap) {
        fileMap = new HashMap<>();
        progressBar.setVisible(true);
        new LoadingBar().start();
        futureFiles = new LinkedList<>();
        for (Map.Entry<String, byte[]> entry : zipArchiveEntryMap.entrySet()) {
            Future<?> f = executorFile.submit(new ConvertMailTemplate(entry));
            futureFiles.add(f);
        }

        new CompleteChecker().start();
    }

    private void processStoreResult(Map<String, String> fileMap) {
        System.out.println("Start store");

        try {
            ZipArchiveOutputStream zaos = new ZipArchiveOutputStream(fileSave);
            for (Map.Entry<String, String> entry : fileMap.entrySet()) {
                ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(entry.getKey());
                zipArchiveEntry.setTime(new Date().getTime());
                zaos.putArchiveEntry(zipArchiveEntry);
                zaos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            zaos.closeArchiveEntry();
            zaos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Done store");
    }

    public static String getCharsetEncode(byte[] buffer) {
        if (buffer == null) {
            throw new RuntimeException("Data can not be null");
        }
        CharsetDetector detector = new CharsetDetector();
        try {
            detector.setText(buffer);
        } catch (UnsupportedCharsetException e) {
            throw new RuntimeException(e);
        }
        String charsetEncode = detector.detect().getName();
        if (charsetEncode == null) {
            charsetEncode = "UTF-8";
        }
        return charsetEncode;
    }

    static class ExtractMailSource extends Thread {

        private final List<String> lineContents;
        private final String searchElement;
        Map<String, String> result;

        public ExtractMailSource(List<String> lineContents, Map<String, String> result, String searchElement) {
            this.lineContents = lineContents;
            this.result = result;
            this.searchElement = searchElement;
        }

        @Override
        public void run() {
            System.out.println(this.getClass());
            Optional<String> lineOptional = lineContents.stream()
                    .filter(line -> line.toUpperCase().contains(searchElement.toUpperCase()))
                    .findFirst();

            int index = lineOptional.map(lineContents::indexOf).orElse(-1);
            if (index != -1){
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(lineContents.get(index));
                while(!lineContents.get(index+1).contains(":")){
                    stringBuilder.append(lineContents.get(index+1));
                    index++;
                }
                result.put(searchElement, stringBuilder.toString());
            }else{
                result.put(searchElement,searchElement+"\n");
            }
            System.out.println("Line done");
        }
    }


    class ConvertMailTemplate extends Thread {
        private final Map.Entry<String, byte[]> entry;


        public ConvertMailTemplate(Map.Entry<String, byte[]> entry) {
            this.entry = entry;
        }


        @Override
        public void run() {
            System.out.println(this.getClass());
            String charset = getCharsetEncode(entry.getValue());
            try {
                List<String> lineContents = Arrays.asList(new String(entry.getValue(), charset).split("\n"));
                Map<String,String> result = new HashMap<>();
                List<Future<?>> futureLineStrings = new ArrayList<>();
                try {
                    for (String searchElement: searchElements){
                        Future<?> f = executorLine.submit(new ExtractMailSource(lineContents, result, searchElement));
                        futureLineStrings.add(f);
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }finally {
                    int firstIndex = lineContents.indexOf(lineContents.stream()
                            .filter(line -> line.contains("Content-Transfer-Encoding:"))
                            .findFirst().orElse("Content-Type:"));

                    int lastIndex = lineContents.indexOf(lineContents.stream()
                            .filter(line -> line.contains("=="))
                            .findFirst()
                            .orElse(lineContents
                                    .stream()
                                    .filter(line -> line.contains("--")).
                                    findFirst().orElse(null)));

                    if (lastIndex == -1) {
                        lastIndex = lineContents.size();
                    }

                    StringBuilder contentBuilder = new StringBuilder();
                    contentBuilder.append("Content:");
                    for (int i = firstIndex+2; i < lastIndex ; i++) {
                        contentBuilder.append(lineContents.get(i));
                    }
                    result.put("Content:", contentBuilder.toString());

                    for(Future<?> future: futureLineStrings){
                        future.get();
                    }

                    StringBuilder stringBuilder = new StringBuilder();
                    for (String searchEle: searchElements){
                        stringBuilder.append(result.get(searchEle));
                    }
                    stringBuilder.append(result.get("Content:"));
                    fileMap.put(entry.getKey(), stringBuilder.toString());
                }
            } catch (IOException | InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    class LoadingBar extends Thread {
        @Override
        public void run() {
            while(fileMap.size() < numberOfFile){
                System.out.println(fileMap.size()+"---"+numberOfFile);
                System.out.println(fileMap.size()*1.0/numberOfFile);
                progressBar.setProgress(fileMap.size()*1.0/numberOfFile);
            }
        }
    }

    class CompleteChecker extends Thread {
        @Override
        public void run() {
            while(true){
                int count = 0;
                for(Future<?> future: futureFiles){
                    if (future.isDone()) {
                        count ++;
                    }
                }
                if (count == futureFiles.size()) {
                    System.out.println("CHECKER DONE");
                    processStoreResult(fileMap);
                    Platform.runLater(ConvertController.this::showAlertSuccess);
                    break;
                }
            }
        }
    }
}