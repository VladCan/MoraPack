package pe.edu.pucp.morapack.airscheduler.bootstrap;

import io.quarkus.runtime.Quarkus;

public class Main {
    public static void main(String ... args) {
          System.out.println("Running custom main method");
          Quarkus.run(args);
      }
}
