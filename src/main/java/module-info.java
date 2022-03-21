module kwmc.tool {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires org.apache.commons.compress;
    requires com.ibm.icu;


    opens kwmc.tool to javafx.fxml;
    exports kwmc.tool;
}