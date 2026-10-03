package com.tbridge.debt.assembler;

import com.tbridge.common.jwt.SesionActual;
import com.tbridge.debt.controller.DebtController;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.dto.response.SimulacionResponse;
import com.tbridge.debt.model.Debt;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.hateoas.server.mvc.BasicLinkBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Lo que se puede hacer con una deuda, segun su estado y quien la mira.
 *
 * <ul>
 *   <li>{@code self}: la deuda completa.</li>
 *   <li>{@code simular} y {@code repactar}: al deudor, mientras este pendiente.</li>
 *   <li>{@code pagar}: al deudor, mientras tenga saldo (pendiente o en convenio).
 *       Es ms-payments; la direccion publica es la misma, el gateway reparte.</li>
 *   <li>{@code enviar-codigo}: a la empresa, mientras siga en cobranza.</li>
 *   <li>{@code certificado}: a cualquiera de los dos, solo si esta pagada.</li>
 * </ul>
 *
 * <p>Solo se ofrecen los caminos que quien mira puede seguir. La autorizacion
 * de verdad la hace el servicio igual: esto evita ofrecer algo que termina en
 * 403.
 */
@Component
public class DebtModelAssembler implements RepresentationModelAssembler<DebtSummaryResponse, EntityModel<DebtSummaryResponse>> {

    @Override
    public EntityModel<DebtSummaryResponse> toModel(DebtSummaryResponse deuda) {
        return EntityModel.of(deuda, enlaces(deuda.id(), deuda.estado()));
    }

    @Override
    public CollectionModel<EntityModel<DebtSummaryResponse>> toCollectionModel(
            Iterable<? extends DebtSummaryResponse> deudas) {
        return RepresentationModelAssembler.super.toCollectionModel(deudas)
                .add(linkTo(methodOn(DebtController.class).list(null)).withSelfRel());
    }

    public EntityModel<DebtDetailResponse> toModel(DebtDetailResponse deuda) {
        EntityModel<DebtDetailResponse> modelo = EntityModel.of(deuda, enlaces(deuda.id(), deuda.estado()));
        return modelo.add(linkTo(methodOn(DebtController.class).list(null)).withRel("deudas"));
    }

    public EntityModel<SimulacionResponse> toModel(Long id, SimulacionResponse simulacion) {
        return EntityModel.of(simulacion,
                linkTo(methodOn(DebtController.class).simulate(null, id, simulacion.plan().months())).withSelfRel(),
                linkTo(methodOn(DebtController.class).repact(null, id, null)).withRel("repactar"),
                linkTo(methodOn(DebtController.class).one(null, id)).withRel("deuda"));
    }

    private static List<Link> enlaces(Long id, Debt.Status estado) {
        List<Link> enlaces = new ArrayList<>();
        enlaces.add(linkTo(methodOn(DebtController.class).one(null, id)).withSelfRel());
        boolean conSaldo = estado == Debt.Status.open || estado == Debt.Status.repacted;

        if (SesionActual.esDeudor()) {
            if (estado == Debt.Status.open) {
                enlaces.add(linkTo(methodOn(DebtController.class).simulate(null, id, null)).withRel("simular"));
                enlaces.add(linkTo(methodOn(DebtController.class).repact(null, id, null)).withRel("repactar"));
            }
            if (conSaldo) {
                enlaces.add(BasicLinkBuilder.linkToCurrentMapping()
                        .slash("api").slash("payments").slash("checkout").withRel("pagar"));
                enlaces.add(linkTo(DebtController.class).slash(id).slash("disputa").withRel("disputar"));
            }
        }
        if (SesionActual.esEmpresa() && conSaldo) {
            //  Por ruta y no con methodOn: codigo() devuelve un record, que es
            //  final, y methodOn necesita poder heredar del tipo que se devuelve.
            enlaces.add(linkTo(DebtController.class).slash(id).slash("codigo").withRel("enviar-codigo"));
        }
        if (SesionActual.esEmpresa() && estado == Debt.Status.disputed) {
            enlaces.add(linkTo(DebtController.class).slash(id).slash("disputa").slash("resolucion")
                    .withRel("resolver-disputa"));
        }
        if (estado == Debt.Status.paid) {
            enlaces.add(linkTo(methodOn(DebtController.class).certificate(null, id)).withRel("certificado"));
        }
        return enlaces;
    }
}
