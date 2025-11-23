package pe.edu.pucp.morapack.airscheduler.engine.infrastructure.io;

import pe.edu.pucp.morapack.airscheduler.engine.infrastructure.model.Pedido;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class LectorPedidoMultiArchivo implements Closeable {
    private static final String SEPARATOR = "-";

    /// Esto representa el estado de lectura, PARA CADA ARCHIVO
    private static class FileState{
        final int index; //índice del archivo
        final Path path; //la ruta correspondiente
        final BufferedReader reader;
        boolean exhausted = false; //Representa el eof

        FileState(int index, Path path, BufferedReader reader) {
            this.index = index;
            this.path = path;
            this.reader = reader;
        }
    }

    ///  Esto representa una entrada en el heap, agrupa un Pedido con su respectivo archivo de origen
    private static class HeapEntry{
        final Pedido pedido;
        final FileState fileState;

        HeapEntry(Pedido pedido, FileState fileState) {
            this.pedido = pedido;
            this.fileState = fileState;
        }
    }

    ///
    /// A partir de aquí, vienen campos internos de la clase
    ///

    private final List<FileState> fileStates = new ArrayList<>();

    /// Esto es un min-heap ordenado por fecha de pedido. Tiene a lo más 1 solo pedido por archivo
    private final PriorityQueue<HeapEntry> heap;

    /// Contador global para los IDs de cada pedido
    private int nextGlobalId = 1;

    ///
    /// Constructor
    ///

    public LectorPedidoMultiArchivo(List<Path> paths) throws IOException {
        //Ordena por fecha (y opcionalmente por id) con el comparator del heap
        this.heap = new PriorityQueue<>(Comparator
                .comparing((HeapEntry e) -> e.pedido.getFecha())
                .thenComparingInt(e -> e.pedido.getIdPedido()));

        int id = 0;
        for (Path path : paths) {
            BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            FileState fs = new FileState(id++, path, br);
            fileStates.add(fs);

            //Leemos la primera línea del archivo para meterla al heap
            HeapEntry entry = leerSiguienteDeArchivo(fs);
            if (entry != null){
                heap.add(entry);
            }
        }
    }

    /// Descartamos todos los pedidos anteriores a la fecha de inicio
    public void saltarHasta(LocalDateTime fechaInicio) throws IOException {
        while (!heap.isEmpty()){
            HeapEntry top = heap.peek();
            if (!top.pedido.getFecha().isBefore(fechaInicio)){
                //El mínimo ya está en la fecha de inicio o después
                break;
            }

            // Descartar este pedido
            heap.poll();

            // Y avanzamos el archivo de donde salió
            HeapEntry siguiente = leerSiguienteDeArchivo(top.fileState);
            if (siguiente != null) {
                heap.add(siguiente);
            }
        }
    }

    /// Retorna todos los pedidos con fecha <= finVentana, avanzando los cursores de cada archivo hasta dicho punto.
    public List<Pedido> leerHasta(LocalDateTime finVentana) throws IOException {
        List<Pedido> pedidosVentana = new ArrayList<>();

        while (!heap.isEmpty()){
            HeapEntry top = heap.peek();
            if (top.pedido.getFecha().isAfter(finVentana)) {
                //El pedido ya supera la ventana, paramos
                break;
            }

            // Este pedido está dentro de la ventana
            heap.poll();
            pedidosVentana.add(top.pedido);


            // Leemos el siguiente pedido del mismo archivo
            HeapEntry siguiente = leerSiguienteDeArchivo(top.fileState);
            if (siguiente != null) {
                heap.add(siguiente);
            }
        }


        return pedidosVentana;
    }

    /// Refleja si ya se leyeron todos los archivos completos
    public boolean terminado(){
        return heap.isEmpty();
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (FileState fs : fileStates) {
            try {
                fs.reader.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    /// Lee la siguiente línea del archivo y la transforma a Pedido
    /// Si terminó, marca el FS como exhausted y retorna null

    private HeapEntry leerSiguienteDeArchivo(FileState fs) throws IOException{
        if (fs.exhausted) return null;

        String line = fs.reader.readLine();
        if (line == null) {
            //Terminó el archivo
            fs.exhausted = true;
            return null;
        }

        line = line.trim();
        if (line.isEmpty()) {
            // línea vacía -> intentar leer la siguiente
            return leerSiguienteDeArchivo(fs);
        }

        Pedido pedido = parseLineaPedido(line);
        return new HeapEntry(pedido, fs);
    }

    /// Parsea una línea con el formato del profesor a un Pedido (como en Pedido.leerProfeNew)
    private Pedido parseLineaPedido(String linea){
        String[] partes = linea.split(SEPARATOR);

        if (partes.length != 7) {
            throw new IllegalArgumentException("Formato inválido (esperado 7 campos): " + linea);
        }

        Pedido pedido = new Pedido();
        //ID incremental global
        pedido.setIdPedido(nextGlobalId++);

        try{
            // partes[1] = YYYYMMDD
            String datePart = partes[1].trim();
            if (datePart.length() != 8) {
                throw new NumberFormatException("Formato YYYYMMDD incorrecto: " + datePart);
            }
            int yyyy = Integer.parseInt(datePart.substring(0, 4));
            int MM = Integer.parseInt(datePart.substring(4, 6));
            int dd = Integer.parseInt(datePart.substring(6, 8));

            // partes[2] = HH
            int hh = Integer.parseInt(partes[2].trim());
            // partes[3] = MM
            int mm = Integer.parseInt(partes[3].trim());

            // partes[4] = destino (código aeropuerto)
            String destino = partes[4].trim();

            // partes[5] = cantidad
            int cantidad = Integer.parseInt(partes[5].trim());

            // partes[6] = idCliente
            int idCliente = Integer.parseInt(partes[6].trim());

            LocalDateTime fecha = LocalDateTime.of(yyyy, MM, dd, hh, mm, 0);

            pedido.setFecha(fecha);
            pedido.setDestino(destino);
            pedido.setCantidad(cantidad);
            pedido.setIdCliente(idCliente);
        }
        catch (NumberFormatException e){
            throw new IllegalArgumentException(
                    "Error de parseo numérico/fecha en línea: " + linea + " | Causa: " + e.getMessage()
            );
        }

        return pedido;
    }

}
