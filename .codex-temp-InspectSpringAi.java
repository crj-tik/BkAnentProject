import java.lang.reflect.*;
public class InspectSpringAi {
  public static void main(String[] args) throws Exception {
    String[] classes = {
      "org.springframework.ai.tool.method.MethodToolCallbackProvider",
      "org.springframework.ai.tool.ToolCallbackProvider",
      "org.springframework.ai.chat.client.ChatClient",
      "org.springframework.ai.tool.annotation.Tool"
    };
    for (String name : classes) {
      Class<?> c = Class.forName(name);
      System.out.println("CLASS=" + name);
      for (Method m : c.getDeclaredMethods()) {
        System.out.println("  " + m.toString());
      }
    }
  }
}
