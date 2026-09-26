package com.arenaai.duagents.tools

/**
 * Engineering decision: a self-contained recursive-descent arithmetic evaluator — no eval(),
 * no reflection, no dynamic code loading, fully sandbox-safe. Supports + - * / % ^,
 * parentheses, unary minus, constants (pi, e) and the functions
 * sqrt sin cos tan asin acos atan log ln abs round floor ceil exp.
 */
object CalculatorEngine {

    fun evaluate(expression: String): Double {
        val tokens = tokenize(expression)
        if (tokens.isEmpty()) throw IllegalArgumentException("Empty expression")
        val parser = Parser(tokens)
        val value = parser.parseExpression()
        parser.expectEnd()
        if (value.isNaN() || value.isInfinite()) {
            throw IllegalArgumentException("Result is not a finite number")
        }
        return value
    }

    fun evaluateToText(expression: String): String {
        val v = evaluate(expression)
        return if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            v.toString()
        }
    }

    private sealed class Token {
        data class Num(val value: Double) : Token()
        data class Ident(val name: String) : Token()
        data class Op(val symbol: Char) : Token()
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val s = input.replace("_", " ").trim()
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    tokens.add(Token.Num(s.substring(start, i).toDouble()))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && (s[i].isLetter() || s[i] == '_')) i++
                    tokens.add(Token.Ident(s.substring(start, i).lowercase()))
                }
                c in "+-*/%^()" -> {
                    tokens.add(Token.Op(c))
                    i++
                }
                else -> throw IllegalArgumentException("Unexpected character '$c' at position $i")
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun next(): Token? = tokens.getOrNull(pos++)

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                val op = peek() as? Token.Op ?: break
                if (op.symbol != '+' && op.symbol != '-') break
                next()
                val rhs = parseTerm()
                value = if (op.symbol == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                val op = peek() as? Token.Op ?: break
                if (op.symbol != '*' && op.symbol != '/' && op.symbol != '%') break
                next()
                val rhs = parseUnary()
                value = when (op.symbol) {
                    '*' -> value * rhs
                    '/' -> value / rhs
                    else -> value % rhs
                }
            }
            return value
        }

        private fun parseUnary(): Double {
            val op = peek() as? Token.Op
            if (op != null && (op.symbol == '-' || op.symbol == '+')) {
                next()
                val v = parseUnary()
                return if (op.symbol == '-') -v else v
            }
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            val op = peek() as? Token.Op ?: return base
            if (op.symbol != '^') return base
            next()
            return Math.pow(base, parseUnary()) // right-associative
        }

        private fun parsePrimary(): Double {
            return when (val t = next()) {
                is Token.Num -> t.value
                is Token.Ident -> {
                    when (t.name) {
                        "pi" -> Math.PI
                        "e" -> Math.E
                        else -> {
                            // Must be a function call.
                            if ((peek() as? Token.Op)?.symbol != '(') {
                                throw IllegalArgumentException("Unknown identifier '${t.name}'")
                            }
                            next() // consume '('
                            val arg = parseExpression()
                            expect(')')
                            applyFunction(t.name, arg)
                        }
                    }
                }
                is Token.Op -> {
                    if (t.symbol == '(') {
                        val v = parseExpression()
                        expect(')')
                        v
                    } else {
                        throw IllegalArgumentException("Unexpected '${t.symbol}'")
                    }
                }
                null -> throw IllegalArgumentException("Unexpected end of expression")
            }
        }

        private fun expect(symbol: Char) {
            val t = next()
            if (t !is Token.Op || t.symbol != symbol) {
                throw IllegalArgumentException("Expected '$symbol'")
            }
        }

        fun expectEnd() {
            if (pos < tokens.size) throw IllegalArgumentException("Unexpected token at position $pos")
        }

        private fun applyFunction(name: String, x: Double): Double = when (name) {
            "sqrt" -> Math.sqrt(x)
            "sin" -> Math.sin(x)
            "cos" -> Math.cos(x)
            "tan" -> Math.tan(x)
            "asin" -> Math.asin(x)
            "acos" -> Math.acos(x)
            "atan" -> Math.atan(x)
            "log" -> Math.log10(x)
            "ln" -> Math.log(x)
            "abs" -> Math.abs(x)
            "round" -> Math.round(x).toDouble()
            "floor" -> Math.floor(x)
            "ceil" -> Math.ceil(x)
            "exp" -> Math.exp(x)
            else -> throw IllegalArgumentException("Unknown function '$name'")
        }
    }
}
