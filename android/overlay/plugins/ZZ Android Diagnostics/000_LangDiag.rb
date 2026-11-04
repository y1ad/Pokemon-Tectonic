# Diagnostico TEMPORAL (Android): muestra por que falla la carga de idioma.
# Messages#loadMessageFile traga cualquier error con un rescue silencioso, asi que
# aqui repetimos la carga del .dat y guardamos el resultado para mostrarlo en pantalla
# justo antes del mensaje "Game language changed to ...".
begin
  alias __android_diag_loadLanguage loadLanguage
  def loadLanguage
    ret = __android_diag_loadLanguage
    begin
      fname = "Data/" + Settings::LANGUAGES[$Options.language][1]
      ex = File.file?(fname)
      line1 = "ex=#{ex} size=#{ex ? File.size(fname) : 0} lang=#{$Options.language}"
      line2 = ""
      begin
        data = nil
        File.open(fname, "rb") { |f| data = Marshal.load(f) }
        line2 = "marshal=#{data.class}"
        line2 += " len=#{data.length}" if data.respond_to?(:length)
        line2 += " s24=#{data[24].class}" if data.is_a?(Array)
      rescue Exception => e
        line2 = "ERR #{e.class}: #{e.message.to_s[0, 90]}"
      end
      $ANDROID_LANG_DIAG = line1 + "\n" + line2
    rescue Exception
    end
    return ret
  end

  alias __android_diag_pbMessage pbMessage
  def pbMessage(message, *args, &block)
    if $ANDROID_LANG_DIAG && message.to_s[/language changed|idioma/i]
      d = $ANDROID_LANG_DIAG
      $ANDROID_LANG_DIAG = nil
      __android_diag_pbMessage(d)
    end
    return __android_diag_pbMessage(message, *args, &block)
  end
rescue Exception
end
