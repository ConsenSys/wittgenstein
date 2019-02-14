package net.consensys.wittgenstein.server;

import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.utils.Reflects;
import net.consensys.wittgenstein.protocols.PingPong;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Allows to run the protocols in a (web)server.
 */
public class Server implements IServer {
  private Protocol protocol;

  public List<? extends Node> getNodeInfo() {
    return protocol.network().allNodes;
  }

  public int getTime() {
    return protocol.network().time;
  }

  private Constructor<?> getConstructor(String fullClassName) {
    Class<?> clazz = Reflects.forName(fullClassName);

    for (Constructor<?> c : clazz.getConstructors()) {
      if (c.getParameterCount() == 1
          && WParameter.class.isAssignableFrom(c.getParameterTypes()[0])) {
        return c;
      }
    }

    throw new IllegalStateException("no constructor in " + fullClassName);
  }

  public void init(String fullClassName, WParameter parameters) {
    Constructor<?> c = getConstructor(fullClassName);
    protocol = (Protocol) Reflects.newInstance(c, parameters);
    protocol.init();
  }

  public List<String> getProtocols() {
    BeanDefinitionRegistry bdr = new SimpleBeanDefinitionRegistry();
    ClassPathBeanDefinitionScanner s = new ClassPathBeanDefinitionScanner(bdr);

    TypeFilter tf = new AssignableTypeFilter(Protocol.class);

    s.addIncludeFilter(tf);
    s.setIncludeAnnotationConfig(false);
    s.scan(PingPong.class.getPackage().getName());

    String[] beans = bdr.getBeanDefinitionNames();
    return Arrays.stream(beans).map(n -> bdr.getBeanDefinition(n).getBeanClassName()).collect(
        Collectors.toList());
  }

  public WParameter getProtocolParameters(String fullClassName) {
    Class<?> clazz = Reflects.forName(fullClassName);
    Constructor<?> bc = null;
    for (Constructor<?> c : clazz.getConstructors()) {
      if (c.getParameterCount() == 1) {
        bc = c;
      }
    }
    if (bc == null) {
      throw new IllegalStateException("no constructor in " + fullClassName);
    }

    return (WParameter) Reflects.newInstance(bc.getParameters()[0].getType());
  }

  public void runMs(int ms) {
    protocol.network().runMs(ms);
  }

  public static void main(String... args) {
    Server server = new Server();
    List<String> ps = server.getProtocols();
    System.out.println(ps);
    String clazz = "net.consensys.wittgenstein.protocols.P2PFlood";
    WParameter params = server.getProtocolParameters(clazz);
    System.out.println(params);
    server.init(clazz, params);
    server.runMs(100);
    System.out.println(server.protocol);
    System.out.println(server.getNodeInfo());
    System.out.println("" + server.getTime());
  }

}