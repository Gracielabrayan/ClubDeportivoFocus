package com.example.clubfocus

import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.data.FechasClub

class RegistrarSocioActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registrar_socio)

        // 1. Botón Volver (cierra la pantalla actual)
        val tvVolver = findViewById<TextView>(R.id.tvVolver)
        tvVolver.setOnClickListener {
            finish()
        }

        // 2. Vinculamos todos los componentes visuales del XML
        val rbSocio = findViewById<RadioButton>(R.id.rbSocio)
        val cbAptoFisico = findViewById<CheckBox>(R.id.cbAptoFisico)
        val etNombre = findViewById<EditText>(R.id.etNombre)
        val etApellido = findViewById<EditText>(R.id.etApellido)
        val etDni = findViewById<EditText>(R.id.etDni)
        val etFechaNacimiento = findViewById<EditText>(R.id.etFechaNacimiento)
        val etCorreo = findViewById<EditText>(R.id.etCorreo)
        val btnGuardarRegistro = findViewById<Button>(R.id.btnGuardarRegistro)

        // 3. Formateador automático para la fecha (agrega las /)
        etFechaNacimiento.doAfterTextChanged { texto ->
            val cadena = texto.toString()
            if ((cadena.length == 2 || cadena.length == 5) && !cadena.endsWith("/")) {
                etFechaNacimiento.setText("$cadena/")
                etFechaNacimiento.setSelection(etFechaNacimiento.text.length)
            }
        }

        // 4. Acción del Botón Guardar Registro (UN SOLO escuchador)
        btnGuardarRegistro.setOnClickListener {

            // A. Leemos todos los datos ingresados
            val nombre = etNombre.text.toString().trim()
            val apellido = etApellido.text.toString().trim()
            val dni = etDni.text.toString().trim()
            val fechaTexto = etFechaNacimiento.text.toString().trim()
            val correo = etCorreo.text.toString().trim()

            // B. Leemos las opciones seleccionadas
            val presentoApto = cbAptoFisico.isChecked
            val esSocio = rbSocio.isChecked

            // C. Validamos que ningún campo obligatorio esté vacío
            if (nombre.isEmpty() || apellido.isEmpty() || dni.isEmpty() || fechaTexto.isEmpty() || correo.isEmpty()) {

                Toast.makeText(
                    this,
                    "Por favor, completa todos los campos del formulario",
                    Toast.LENGTH_SHORT
                ).show()

            } else if (!presentoApto) {

                // Validación de la regla del club
                Toast.makeText(
                    this,
                    "Debe presentar el Apto Físico para registrarse",
                    Toast.LENGTH_SHORT
                ).show()

            } else if (!(android.util.Patterns.EMAIL_ADDRESS.matcher(correo).matches())) {

                // Validación de email formato válido
                Toast.makeText(
                    this,
                    "Ingrese un mail válido",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                if (!FechasClub.nacimientoValido(fechaTexto)) {
                    Toast.makeText(
                        this,
                        R.string.fecha_nacimiento_invalida,
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                val resultado = try {
                    ClientesDBHelper(this).use { dbHelper ->
                        if (dbHelper.existeCliente(dni)) {
                            Toast.makeText(
                                this,
                                "Ya existe un cliente o socio con ese DNI",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }

                        dbHelper.registrarClienteConCuotaInicial(
                            nombre = nombre,
                            apellido = apellido,
                            dni = dni,
                            fechaNacimiento = fechaTexto,
                            email = correo,
                            aptoFisico = presentoApto,
                            esSocio = esSocio
                        )
                    }
                } catch (_: SQLiteException) {
                    -1L
                }

                if (resultado != -1L) {
                    val tipoUsuario = if (esSocio) "Socio" else "No Socio"
                    Toast.makeText(
                        this,
                        "$tipoUsuario $nombre $apellido registrado correctamente",
                        Toast.LENGTH_SHORT
                    ).show()

                    val intent = Intent(this, RegistroExitosoActivity::class.java)
                    intent.putExtra("clienteId", resultado.toInt())
                    startActivity(intent)

                    finish()
                } else {
                    Toast.makeText(
                        this,
                        R.string.registro_error_alta,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
