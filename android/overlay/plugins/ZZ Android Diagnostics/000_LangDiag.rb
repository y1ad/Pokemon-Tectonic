# Diagnostico TEMPORAL (Android): muestra por que falla la carga de idioma.
# Messages#loadMessageFile traga cualquier error con un rescue silencioso. Aqui
# interceptamos pbLoadMessages (la usan tanto el arranque como Opciones > Language),
# repetimos la carga del .dat y mostramos el resultado en el siguiente pbMessage.
begin
  alias __android_diag_pbLoadMessages pbLoadMessages
  def pbLoadMessages(file)
    ret = __android_diag_pbLoadMessages(file)
    begin
      unless file.to_s[/english/i]
        ex = File.file?(file)
        line1 = "#{file} ex=#{ex} size=#{ex ? File.size(file) : 0} ret=#{ret.class}"
        line2 = ""
        begin
          data = nil
          File.open(file, "rb") { |f| data = Marshal.load(f) }
          line2 = "marshal=#{data.class}"
          line2 += " len=#{data.length}" if data.respond_to?(:length)
          line2 += " s24=#{data[24].class}" if data.is_a?(Array)
        rescue Exception => e
          line2 = "ERR #{e.class}: #{e.message.to_s[0, 90]}"
        end
        $ANDROID_LANG_DIAG = line1 + "\n" + line2
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
