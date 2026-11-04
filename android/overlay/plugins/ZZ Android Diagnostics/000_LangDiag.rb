# Parche + diagnostico (Android) para la carga de idioma.
#
# Sintoma: Marshal.load(File) falla con "marshal data too short" en Android aunque el
# archivo existe con su tamano completo, y Messages#loadMessageFile se traga el error.
# Intento de arreglo: si la carga normal falla, leer el .dat ENTERO a memoria
# (File.binread, o lectura por trozos) y hacer Marshal.load sobre el String.
# Ademas deja un mensaje de diagnostico que se muestra una vez en el siguiente pbMessage.
begin
  alias __android_diag_pbLoadMessages pbLoadMessages
  def pbLoadMessages(file)
    ret = __android_diag_pbLoadMessages(file)
    begin
      if ret.nil? && !file.to_s[/english/i]
        size = (File.size(file) rescue -1)
        info = ["#{file} size=#{size}"]
        attempts = []
        data = nil
        begin
          d = File.binread(file)
          attempts << "binread=#{d.bytesize}"
          data = d if d.bytesize == size
        rescue Exception => e
          attempts << "binread ERR #{e.class}"
        end
        if data.nil?
          begin
            buf = "".b
            File.open(file, "rb") do |f|
              while (c = f.read(65536))
                buf << c
              end
            end
            attempts << "chunk=#{buf.bytesize}"
            data = buf if buf.bytesize == size
          rescue Exception => e
            attempts << "chunk ERR #{e.class}"
          end
        end
        info << attempts.join(" ")
        if data
          crc = (Zlib.crc32(data).to_s(16) rescue "?")
          info << "crc=#{crc}"
          begin
            msgs = Marshal.load(data)
            if msgs.is_a?(Array)
              MessageTypes.class_variable_get(:@@messages).instance_variable_set(:@messages, msgs)
              ret = msgs
              info << "FIX OK"
            else
              info << "root=#{msgs.class}"
            end
          rescue Exception => e
            info << "load ERR #{e.class}: #{e.message.to_s[0, 80]}"
          end
        end
        $ANDROID_LANG_DIAG = info.join("\n")
      end
    rescue Exception
    end
    return ret
  end

  alias __android_diag_pbMessage pbMessage
  def pbMessage(message, *args, &block)
    if $ANDROID_LANG_DIAG
      d = $ANDROID_LANG_DIAG
      $ANDROID_LANG_DIAG = nil
      __android_diag_pbMessage(d)
    end
    return __android_diag_pbMessage(message, *args, &block)
  end
rescue Exception
end
