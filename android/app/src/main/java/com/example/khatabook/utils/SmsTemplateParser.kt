package com.example.khatabook.utils

object SmsTemplateParser {
    
    fun parse(template: String, variables: Map<String, String>): String {
        var parsed = template
        variables.forEach { (key, value) ->
            parsed = parsed.replace("{{$key}}", value)
        }
        return parsed
    }
}
